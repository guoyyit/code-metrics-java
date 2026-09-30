package com.github.guoyyit.codemetricsjava.web;

import com.github.guoyyit.codemetricsjava.analysis.SuppressionService;
import com.github.guoyyit.codemetricsjava.model.Suppression;
import com.github.guoyyit.codemetricsjava.model.SuppressionRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Suppression management endpoints. Every grant/revocation is durable and
 * recorded in the audit log.
 */
@RestController
@RequestMapping("/api/suppressions")
public class SuppressionController {

    private final SuppressionService suppressionService;

    public SuppressionController(SuppressionService suppressionService) {
        this.suppressionService = suppressionService;
    }

    @GetMapping
    public List<Suppression> list() {
        return suppressionService.list();
    }

    @PostMapping
    public Suppression grant(@RequestBody SuppressionRequest request) {
        return suppressionService.grant(request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Suppression> revoke(
            @PathVariable String id,
            @RequestParam(defaultValue = "anonymous") String revokedBy) {
        return suppressionService.revoke(id, revokedBy)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
