// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package build.buildfarm.worker;

import build.buildfarm.common.DigestUtil;
import build.buildfarm.instance.stub.Chunker;
import build.buildfarm.v1test.CASInsertionPolicy;
import com.google.common.collect.Lists;
import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.OutputFile;
import build.bazel.remote.execution.v2.Tree;
import com.google.protobuf.ByteString;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** UploadManifest adds output metadata to a {@link ActionResult}. */
/** FIXME move into worker implementation and implement 'fast add' with this for sharding */
class UploadManifest {
  private static final String ERR_NO_SUCH_FILE_OR_DIR = " (No such file or directory)";

  private static final LinkOption[] NO_LINK_OPTION = new LinkOption[0];
  // This isn't generally safe; we rely on the file system APIs not modifying the array.
  private static final LinkOption[] NOFOLLOW_LINKS_OPTION =
      new LinkOption[] { LinkOption.NOFOLLOW_LINKS };

  private final DigestUtil digestUtil;
  private final ActionResult.Builder result;
  private final Path execRoot;
  private final boolean allowSymlinks;
  private final int inlineContentLimit;
  private final Map<Digest, Path> digestToFile;
  private final Map<Digest, Chunker> digestToChunkers;
  private transient int inlineContentBytes;

  /**
   * Create an UploadManifest from an ActionResult builder and an exec root. The ActionResult
   * builder is populated through a call to {@link #addFile(Digest, Path)}.
   */
  public UploadManifest(
      DigestUtil digestUtil,
      ActionResult.Builder result,
      Path execRoot,
      boolean allowSymlinks,
      int inlineContentLimit) {
    this.digestUtil = digestUtil;
    this.result = result;
    this.execRoot = execRoot;
    this.allowSymlinks = allowSymlinks;
    this.inlineContentLimit = inlineContentLimit;

    this.digestToFile = new HashMap<>();
    this.digestToChunkers = new HashMap<>();
    this.inlineContentBytes = 0;
  }

  /**
   * Add a collection of files to the UploadManifest.
   */
  public void addFiles(Iterable<Path> files, CASInsertionPolicy policy)
      throws IllegalStateException, IOException, InterruptedException {
    for (Path file : files) {
      FileStatus stat = statIfFound(file, /* followSymlinks= */ false);
      if (stat == null) {
        // We ignore requested results that have not been generated by the action.
        continue;
      }
      if (stat.isDirectory()) {
        mismatchedOutput(file);
      } else if (stat.isFile()) {
        addFile(file, policy);
      } else if (allowSymlinks && stat.isSymbolicLink()) {
        /** FIXME symlink to directory? */
        addFile(file, policy);
      } else {
        illegalOutput(file);
      }
    }
  }

  /**
   * Add a collection of directories to the UploadManifest. Adding a directory has the
   * effect of 1) uploading a {@link Tree} protobuf message from which the whole structure of the
   * directory, including the descendants, can be reconstructed and 2) uploading all the
   * non-directory descendant files.
   */
  public void addDirectories(Iterable<Path> dirs)
      throws IllegalStateException, IOException, InterruptedException {
    for (Path dir : dirs) {
      FileStatus stat = statIfFound(dir, /* followSymlinks= */ false);
      if (stat == null) {
        // We ignore requested results that have not been generated by the action.
        continue;
      }
      if (stat.isDirectory()) {
        addDirectory(dir);
      } else if (stat.isFile() || stat.isSymbolicLink()) {
        mismatchedOutput(dir);
      } else {
        illegalOutput(dir);
      }
    }
  }

  private void addFiles(Iterable<Path> files, boolean isDirectory, CASInsertionPolicy policy)
      throws IllegalStateException, IOException, InterruptedException {
  }

  /** Map of digests to file paths to upload. */
  public Map<Digest, Path> getDigestToFile() {
    return digestToFile;
  }

  /**
   * Map of digests to chunkers to upload. When the file is a regular, non-directory file it is
   * transmitted through {@link #getDigestToFile()}. When it is a directory, it is transmitted as
   * a {@link Tree} protobuf message through {@link #getDigestToChunkers()}.
   */
  public Map<Digest, Chunker> getDigestToChunkers() {
    return digestToChunkers;
  }

  public void addContent(ByteString content, CASInsertionPolicy policy, Consumer<ByteString> setRaw, Consumer<Digest> setDigest) {
    boolean withinLimit = inlineContentBytes + content.size() <= inlineContentLimit;
    if (withinLimit) {
      setRaw.accept(content);
      inlineContentBytes += content.size();
    } else {
      setRaw.accept(ByteString.EMPTY);
    }
    if (policy.equals(CASInsertionPolicy.ALWAYS_INSERT)
        || (!withinLimit && policy.equals(CASInsertionPolicy.INSERT_ABOVE_LIMIT))) {
      Digest digest = digestUtil.compute(content);
      setDigest.accept(digest);
      digestToChunkers.put(digest, new Chunker(content, digest));
    }
  }

  private void addFile(Path file, CASInsertionPolicy policy) throws IOException {
    Digest digest = digestUtil.compute(file);
    OutputFile.Builder builder = result
        .addOutputFilesBuilder()
        .setPath(execRoot.relativize(file).toString())
        .setIsExecutable(Files.isExecutable(file))
        .setDigest(digest);
    digestToFile.put(digest, file);
  }

  private void addDirectory(Path dir) throws IllegalStateException, IOException {
    Tree.Builder tree = Tree.newBuilder();
    Directory root = computeDirectory(dir, tree);
    tree.setRoot(root);

    ByteString blob = tree.build().toByteString();
    Digest digest = digestUtil.compute(blob);
    Chunker chunker = new Chunker(blob, digest);

    if (result != null) {
      result
          .addOutputDirectoriesBuilder()
          .setPath(execRoot.relativize(dir).toString())
          .setTreeDigest(digest);
    }

    digestToChunkers.put(chunker.digest(), chunker);
  }

  private LinkOption[] linkOpts(boolean followSymlinks) {
    return followSymlinks ? NO_LINK_OPTION : NOFOLLOW_LINKS_OPTION;
  }

  /**
   * Returns the status of a file.
   */
  private FileStatus stat(final Path path, final boolean followSymlinks) throws IOException {
    final BasicFileAttributes attributes;
    try {
      attributes =
          Files.readAttributes(path, BasicFileAttributes.class, linkOpts(followSymlinks));
    } catch (java.nio.file.FileSystemException e) {
      throw new FileNotFoundException(path + ERR_NO_SUCH_FILE_OR_DIR);
    }
    FileStatus status = new FileStatus() {
      @Override
      public boolean isFile() {
        return attributes.isRegularFile() || isSpecialFile();
      }

      @Override
      public boolean isSpecialFile() {
        return attributes.isOther();
      }

      @Override
      public boolean isDirectory() {
        return attributes.isDirectory();
      }

      @Override
      public boolean isSymbolicLink() {
        return attributes.isSymbolicLink();
      }

      @Override
      public long getSize() throws IOException {
        return attributes.size();
      }

      @Override
      public long getLastModifiedTime() throws IOException {
        return attributes.lastModifiedTime().toMillis();
      }

      @Override
      public long getLastChangeTime() {
        // This is the best we can do with Java NIO...
        return attributes.lastModifiedTime().toMillis();
      }

      @Override
      public long getNodeId() {
        // TODO(bazel-team): Consider making use of attributes.fileKey().
        return -1;
      }
    };

    return status;
  }

  /**
   * Like stat(), but returns null on failures instead of throwing.
   */
  private FileStatus statNullable(Path path, boolean followSymlinks) {
    try {
      return stat(path, followSymlinks);
    } catch (IOException e) {
      return null;
    }
  }

  private FileStatus statIfFound(Path path, boolean followSymlinks) {
    try {
      return stat(path, followSymlinks);
    } catch (FileNotFoundException e) {
      return null;
    } catch (IOException e) {
      // If this codepath is ever hit, then this method should be rewritten to properly distinguish
      // between not-found exceptions and others.
      throw new IllegalStateException(e);
    }
  }

  private static Dirent.Type direntTypeFromStat(FileStatus stat) {
    if (stat == null) {
      return Dirent.Type.UNKNOWN;
    }
    if (stat.isSpecialFile()) {
      return Dirent.Type.UNKNOWN;
    }
    if (stat.isFile()) {
      return Dirent.Type.FILE;
    }
    if (stat.isDirectory()) {
      return Dirent.Type.DIRECTORY;
    }
    if (stat.isSymbolicLink()) {
      return Dirent.Type.SYMLINK;
    }
    return Dirent.Type.UNKNOWN;
  }

  private List<Dirent> readdir(Path path, boolean followSymlinks) throws IOException {
    List<Dirent> dirents = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
      for (Path file : stream) {
        Dirent.Type type = direntTypeFromStat(statNullable(file, followSymlinks));
        dirents.add(new Dirent(file.getFileName().toString(), type));
      }
    }
    return dirents;
  }

  private Directory computeDirectory(Path path, Tree.Builder tree)
      throws IllegalStateException, IOException {
    Directory.Builder b = Directory.newBuilder();

    List<Dirent> sortedDirent = readdir(path, /* followSymlinks= */ false);
    sortedDirent.sort(Comparator.comparing(Dirent::getName));

    for (Dirent dirent : sortedDirent) {
      String name = dirent.getName();
      Path child = path.resolve(name);
      if (dirent.getType() == Dirent.Type.DIRECTORY) {
        Directory dir = computeDirectory(child, tree);
        b.addDirectoriesBuilder().setName(name).setDigest(digestUtil.compute(dir));
        tree.addChildren(dir);
      } else if (dirent.getType() == Dirent.Type.FILE
          || (dirent.getType() == Dirent.Type.SYMLINK && allowSymlinks)) {
        Digest digest = digestUtil.compute(child);
        b.addFilesBuilder().setName(name).setDigest(digest).setIsExecutable(Files.isExecutable(child));
        digestToFile.put(digest, child);
      } else {
        illegalOutput(child);
      }
    }

    return b.build();
  }

  private void mismatchedOutput(Path what) throws IllegalStateException, IOException {
    String kind = Files.isSymbolicLink(what)
        ? "symbolic link" : Files.isDirectory(what) ? "directory" : "file";
    String expected = kind.equals("directory") ? "file" : "directory";
    throw new IllegalStateException(
        String.format(
            "Output %s is a %s. It was expected to be a %s.",
            execRoot.relativize(what), kind, expected));
  }

  private void illegalOutput(Path what) throws IllegalStateException, IOException {
    String kind = Files.isSymbolicLink(what) ? "symbolic link" : "special file";
    throw new IllegalStateException(
        String.format(
            "Output %s is a %s. Only regular files and directories may be "
                + "uploaded to a remote cache. "
                + "Change the file type or use --remote_allow_symlink_upload.",
            execRoot.relativize(what), kind));
  }
}