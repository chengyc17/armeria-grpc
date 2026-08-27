package org.example.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtoBuildServiceTest {

    private final ProtoBuildService service = new ProtoBuildService();

    @Test
    void rejectsCoordinatesThatEscapeTheWorkingDirectory() {
        MultipartFile file = file("order.proto");

        assertThrows(IllegalArgumentException.class,
                () -> service.buildAndDeploy(file, "com.example", "..", "1.0.0"));
        assertThrows(IllegalArgumentException.class,
                () -> service.buildAndDeploy(file, "com.example", "../..", "1.0.0"));
        assertThrows(IllegalArgumentException.class,
                () -> service.buildAndDeploy(file, "com.example", "a/b", "1.0.0"));
    }

    @Test
    void rejectsXmlHostileOrBlankCoordinates() {
        MultipartFile file = file("order.proto");

        assertThrows(IllegalArgumentException.class,
                () -> service.buildAndDeploy(file, "com.example", "artifact</artifactId>", "1.0.0"));
        assertThrows(IllegalArgumentException.class,
                () -> service.buildAndDeploy(file, "com.example", "artifact", "1.0</version>"));
        assertThrows(IllegalArgumentException.class,
                () -> service.buildAndDeploy(file, " ", "artifact", "1.0.0"));
    }

    @Test
    void rejectsNonProtoFiles() {
        MultipartFile file = file("order.txt");

        assertThrows(IllegalArgumentException.class,
                () -> service.buildAndDeploy(file, "com.example", "order", "1.0.0"));
    }

    private static MultipartFile file(String name) {
        return new MultipartFile() {
            @Override
            public String getName() {
                return "file";
            }

            @Override
            public String getOriginalFilename() {
                return name;
            }

            @Override
            public String getContentType() {
                return "application/octet-stream";
            }

            @Override
            public boolean isEmpty() {
                return true;
            }

            @Override
            public long getSize() {
                return 0;
            }

            @Override
            public byte[] getBytes() throws IOException {
                return new byte[0];
            }

            @Override
            public InputStream getInputStream() throws IOException {
                return new ByteArrayInputStream(new byte[0]);
            }

            @Override
            public void transferTo(java.io.File dest) throws IOException {
                throw new UnsupportedOperationException();
            }
        };
    }
}
