package org.example.service;

import org.apache.maven.cli.MavenCli;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;

@Service
public class ProtoBuildService {

    private static final Logger log = LoggerFactory.getLogger(ProtoBuildService.class);

    @Value("${maven.working-dir}")
    private String workingDir;

    @Value("${maven.deploy.release.server-id}")
    private String releaseServerId;

    @Value("${maven.deploy.release.url}")
    private String releaseUrl;

    @Value("${maven.deploy.snapshot.server-id}")
    private String snapshotServerId;

    @Value("${maven.deploy.snapshot.url}")
    private String snapshotUrl;

    @Value("${maven.deploy.username}")
    private String username;

    @Value("${maven.deploy.password}")
    private String password;

    public String buildAndDeploy(MultipartFile file,
                                  String groupId,
                                  String artifactId,
                                  String version) throws Exception {

        if (groupId == null || groupId.isBlank()
                || artifactId == null || artifactId.isBlank()
                || version == null || version.isBlank()) {
            throw new IllegalArgumentException("groupId, artifactId, version are required");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.endsWith(".proto")) {
            throw new IllegalArgumentException("file must be a .proto file");
        }

        // Resolve workingDir to absolute path; fall back to java.io.tmpdir if not set
        Path baseDir;
        if (workingDir != null && !workingDir.isBlank()) {
            baseDir = Paths.get(workingDir).toAbsolutePath();
        } else {
            baseDir = Paths.get(System.getProperty("java.io.tmpdir"), "proto-build");
        }
        Path projectDir = baseDir.resolve(artifactId);
        // Clean up any leftover from a previous failed build
        deleteRecursively(projectDir);
        try {
            Path protoDir = projectDir.resolve("src/main/proto");
            Files.createDirectories(protoDir);
            Path destPath = protoDir.resolve(originalFilename);
            // use Files.copy instead of transferTo to avoid FileNotFoundException
            // when Tomcat temp dir and target dir are on different filesystems (Windows)
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, destPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            String pomTemplate = readTemplate("templates/pom-template.xml");
            String pom = pomTemplate
                    .replace("{{groupId}}", groupId)
                    .replace("{{artifactId}}", artifactId)
                    .replace("{{version}}", version)
                    .replace("{{release.server.id}}", releaseServerId)
                    .replace("{{release.url}}", releaseUrl)
                    .replace("{{snapshot.server.id}}", snapshotServerId)
                    .replace("{{snapshot.url}}", snapshotUrl);
            Files.writeString(projectDir.resolve("pom.xml"), pom);

            String settingsTemplate = readTemplate("templates/settings-template.xml");
            String settings = settingsTemplate
                    .replace("{{release.server.id}}", releaseServerId)
                    .replace("{{snapshot.server.id}}", snapshotServerId)
                    .replace("{{server.username}}", username)
                    .replace("{{server.password}}", password);
            Path settingsPath = projectDir.resolve("settings.xml");
            Files.writeString(settingsPath, settings);

            // Use Maven Embedder — no external "mvn" command needed.
            // Maven stdout/stderr are line-buffered: each line is logged via
            // SLF4J (so it appears with timestamps and formatting) and also
            // captured in ByteArrayOutputStream for error reporting.
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            MavenCli cli = new MavenCli();
            PrintStream outPs = new PrintStream(new MavenLogOutputStream(stdout, log, false), true);
            PrintStream errPs = new PrintStream(new MavenLogOutputStream(stderr, log, true), true);
            int exitCode;
            String oldMavenProjectDir = System.getProperty(
                    "maven.multiModuleProjectDirectory");
            try {
                // MavenCli internally uses TCCL to discover Sisu/Guice
                // components. In Spring Boot embedded Tomcat the TCCL is
                // already the application ClassLoader which sees all Maven
                // jars — no switching needed. The system property is still
                // required because "mvn" normally sets it from its shell script.
                System.setProperty("maven.multiModuleProjectDirectory",
                        projectDir.toAbsolutePath().toString());
                // 单线程下载：避免 maven-resolver 并行下载线程互锁导致卡死
                // 超时参数：网络异常时快速失败，而不是无限挂起
                exitCode = cli.doMain(
                        new String[]{"clean", "deploy", "-X",
                                "-Daether.connector.basic.threads=1",
                                "-Daether.connector.connectTimeout=30000",
                                "-Daether.connector.requestTimeout=30000",
                                "-DaltDeploymentRepository=" + (version.endsWith("-SNAPSHOT")
                                        ? snapshotServerId + "::default::" + snapshotUrl
                                        : releaseServerId + "::default::" + releaseUrl),
                                "-s", settingsPath.toAbsolutePath().toString()},
                        projectDir.toString(),
                        outPs,
                        errPs
                );
            } finally {
                if (oldMavenProjectDir == null) {
                    System.clearProperty("maven.multiModuleProjectDirectory");
                } else {
                    System.setProperty("maven.multiModuleProjectDirectory",
                            oldMavenProjectDir);
                }
            }
            outPs.close();
            errPs.close();
            String output = stdout.toString();

            if (exitCode != 0) {
                throw new RuntimeException("Maven build failed (exit " + exitCode + "):\n"
                        + output + "\n" + stderr.toString());
            }

            return groupId + ":" + artifactId + ":" + version;
        } catch (Exception e) {
            log.error("Maven build/deploy failed", e);
            throw e;
        } finally {
            deleteRecursively(projectDir);
        }
    }

    private String readTemplate(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        try (InputStream is = resource.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (IOException e) {
            // best-effort cleanup
        }
    }

    /**
     * OutputStream that writes bytes to both a capture buffer and an SLF4J
     * logger. Lines are buffered until {@code \n} and then logged as complete
     * lines — so Maven output appears in the application log with proper
     * timestamps and formatting.
     */
    private static class MavenLogOutputStream extends OutputStream {

        private final ByteArrayOutputStream capture;
        private final Logger logger;
        private final boolean warnLevel;
        private final StringBuilder lineBuf = new StringBuilder();

        MavenLogOutputStream(ByteArrayOutputStream capture, Logger logger, boolean warnLevel) {
            this.capture = capture;
            this.logger = logger;
            this.warnLevel = warnLevel;
        }

        @Override
        public void write(int b) throws IOException {
            capture.write(b);
            char c = (char) b;
            if (c == '\n') {
                flushLine();
            } else if (c != '\r') {
                lineBuf.append(c);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            capture.write(b, off, len);
            for (int i = off; i < off + len; i++) {
                char c = (char) b[i];
                if (c == '\n') {
                    flushLine();
                } else if (c != '\r') {
                    lineBuf.append(c);
                }
            }
        }

        @Override
        public void flush() throws IOException {
            capture.flush();
            if (lineBuf.length() > 0) {
                flushLine();
            }
        }

        @Override
        public void close() throws IOException {
            flush();
            capture.close();
        }

        private void flushLine() {
            String line = lineBuf.toString();
            lineBuf.setLength(0);
            if (warnLevel) {
                logger.warn("[mvn] {}", line);
            } else {
                logger.info("[mvn] {}", line);
            }
        }
    }
}
