package com.jmip.controller;

import com.jmip.dto.PagedResponse;
import com.jmip.dto.admin.AdminDtos.DataQuality;
import com.jmip.dto.admin.AdminDtos.Overview;
import com.jmip.dto.admin.AdminDtos.SourceStatusRequest;
import com.jmip.dto.admin.AdminDtos.UserDetail;
import com.jmip.dto.admin.AdminDtos.UserSummary;
import com.jmip.dto.etl.JobSourceResponse;
import com.jmip.service.admin.AdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * V8.9: platform administration. Every path here is ADMIN-only in the security configuration,
 * decided from the session's authorities; nothing in a request can claim a role. There is no
 * SQL or command execution of any kind, only fixed reads and the job-source switch.
 */
@RestController
@Validated
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService service;

    public AdminController(AdminService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public Overview overview() {
        return service.overview();
    }

    @GetMapping("/data-quality")
    public DataQuality dataQuality() {
        return service.dataQuality();
    }

    @GetMapping("/users")
    public PagedResponse<UserSummary> users(@RequestParam(required = false) @Size(max = 100) String q,
                                            @RequestParam(required = false) @Pattern(regexp = "USER|ADMIN") String role,
                                            @RequestParam(required = false) Boolean verified,
                                            @RequestParam(defaultValue = "0") @Min(0) int page,
                                            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.users(q, role, verified, page, size);
    }

    @GetMapping("/users/{id}")
    public UserDetail user(@PathVariable UUID id) {
        return service.user(id);
    }

    /** Enables or disables a job source; its records are rejected by the ETL while it is inactive. */
    @PatchMapping("/job-sources/{id}")
    public JobSourceResponse setSourceActive(@PathVariable long id, @Valid @RequestBody SourceStatusRequest request) {
        return service.setSourceActive(id, request.active());
    }
}
