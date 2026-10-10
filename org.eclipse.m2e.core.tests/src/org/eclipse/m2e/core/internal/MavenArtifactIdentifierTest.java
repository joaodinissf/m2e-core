/*******************************************************************************
 * Copyright (c) 2026 João Dinis Ferreira and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/

package org.eclipse.m2e.core.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.eclipse.m2e.core.embedder.ArtifactKey;


public class MavenArtifactIdentifierTest {

  private Path temp;

  @Before
  public void createTempDirectory() throws IOException {
    temp = Files.createTempDirectory(getClass().getSimpleName());
  }

  @After
  public void deleteTempDirectory() throws IOException {
    FileUtils.deleteDirectory(temp.toFile());
  }

  private static final FileTime MTIME = FileTime.fromMillis(1_700_000_000_000L);

  /** A jar with one stored pom.properties per GAV ("g:a:v"); GAVs of equal length give jars of equal length. */
  private static byte[] jarBytes(String... gavs) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (JarOutputStream out = new JarOutputStream(bytes)) {
      for(String gav : gavs) {
        String[] g = gav.split(":");
        putStored(out, "META-INF/maven/" + g[0] + "/" + g[1] + "/pom.properties",
            ("groupId=" + g[0] + "\nartifactId=" + g[1] + "\nversion=" + g[2] + "\n").getBytes(StandardCharsets.UTF_8));
      }
      putStored(out, "placeholder.txt", new byte[] {'x'});
    }
    return bytes.toByteArray();
  }

  private static void putStored(JarOutputStream out, String name, byte[] content) throws IOException {
    JarEntry entry = new JarEntry(name);
    entry.setMethod(ZipEntry.STORED);
    entry.setSize(content.length);
    CRC32 crc = new CRC32();
    crc.update(content);
    entry.setCrc(crc.getValue());
    entry.setTime(MTIME.toMillis());
    out.putNextEntry(entry);
    out.write(content);
    out.closeEntry();
  }

  private static void write(Path file, byte[] content, FileTime mtime) throws IOException {
    Files.write(file, content);
    Files.setLastModifiedTime(file, mtime);
  }

  private static List<ArtifactKey> identify(Path file) {
    return new ArrayList<>(MavenArtifactIdentifier.identify(file.toFile()));
  }

  private static ArtifactKey key(String g, String a, String v) {
    return new ArtifactKey(g, a, v, null);
  }

  @Test
  public void testIdentifyFromPomProperties() throws IOException {
    Path jar = temp.resolve("a.jar");
    write(jar, jarBytes("g1:a1:1.0"), MTIME);
    assertEquals(List.of(key("g1", "a1", "1.0")), identify(jar));
  }

  @Test
  public void testUnchangedArchiveIsNotScannedAgain() throws IOException {
    Path jar = temp.resolve("b.jar");
    byte[] first = jarBytes("g1:a1:1.0");
    byte[] second = jarBytes("g2:a2:2.0");
    assertEquals(first.length, second.length);

    write(jar, first, MTIME);
    assertEquals(List.of(key("g1", "a1", "1.0")), identify(jar));

    // same size and modification time: the cached answer is used, so the new content is not seen
    write(jar, second, MTIME);
    assertEquals(List.of(key("g1", "a1", "1.0")), identify(jar));

    // a new modification time invalidates the cached answer
    Files.setLastModifiedTime(jar, FileTime.fromMillis(MTIME.toMillis() + 2000));
    assertEquals(List.of(key("g2", "a2", "2.0")), identify(jar));
  }

  @Test
  public void testUnreadableArchiveIsNotCachedAsUnidentified() throws IOException {
    Path jar = temp.resolve("c.jar");
    byte[] valid = jarBytes("g1:a1:1.0");
    byte[] garbage = new byte[valid.length];

    write(jar, garbage, MTIME);
    assertTrue(identify(jar).isEmpty());

    // same size and modification time as the unreadable content: a cached negative would hide this
    write(jar, valid, MTIME);
    assertEquals(List.of(key("g1", "a1", "1.0")), identify(jar));
  }

  @Test
  public void testOrderIsStableAcrossCachedCalls() throws IOException {
    Path jar = temp.resolve("d.jar");
    write(jar, jarBytes("g1:a1:1.0", "g2:a2:2.0", "g3:a3:3.0"), MTIME);
    List<ArtifactKey> expected = List.of(key("g1", "a1", "1.0"), key("g2", "a2", "2.0"), key("g3", "a3", "3.0"));
    assertEquals(expected, identify(jar));
    assertEquals(expected, identify(jar));
  }

  @Test
  public void testDirectoryIsScannedEveryTime() throws IOException {
    Path dir = Files.createDirectories(temp.resolve("classes"));
    Path props = dir.resolve("META-INF/maven/g1/a1/pom.properties");
    Files.createDirectories(props.getParent());
    Files.writeString(props, "groupId=g1\nartifactId=a1\nversion=1.0\n", StandardCharsets.UTF_8);
    assertEquals(List.of(key("g1", "a1", "1.0")), identify(dir));

    Files.writeString(props, "groupId=g1\nartifactId=a1\nversion=2.0\n", StandardCharsets.UTF_8);
    assertEquals(List.of(key("g1", "a1", "2.0")), identify(dir));
  }
}
