package com.armeria.grpc.api.controller;

import com.armeria.grpc.api.service.ProtoBuildService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

@RestController
@RequestMapping("/api/proto")
public class ProtoUploadController {

    private static final Logger log = LoggerFactory.getLogger(ProtoUploadController.class);

    private final ProtoBuildService protoBuildService;
    private final ReentrantLock buildLock = new ReentrantLock();

    public ProtoUploadController(ProtoBuildService protoBuildService) {
        this.protoBuildService = protoBuildService;
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, String>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("groupId") String groupId,
            @RequestParam("artifactId") String artifactId,
            @RequestParam("version") String version) {

        buildLock.lock();
        try {
            String coordinates = protoBuildService.buildAndDeploy(file, groupId, artifactId, version);
            return ResponseEntity.ok(Map.of(
                    "message", "deploy success",
                    "coordinates", coordinates
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "invalid request",
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("Proto build/deploy failed", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "build failed",
                    "error", "see server logs for details"
            ));
        } finally {
            buildLock.unlock();
        }
    }
}
