package com.jmip.controller;

import com.jmip.dto.auth.AccountDtos.DeleteAccountRequest;
import com.jmip.dto.auth.AccountDtos.PasswordChangeRequest;
import com.jmip.dto.auth.AccountDtos.ProfileRequest;
import com.jmip.dto.auth.UserResponse;
import com.jmip.service.auth.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** V9.17: the signed-in user's account settings. There is no user parameter; the session decides. */
@RestController
@RequestMapping("/api/account")
public class AccountController {

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    @PatchMapping("/profile")
    public UserResponse changeName(@Valid @RequestBody ProfileRequest request) {
        return service.changeName(request.fullName());
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody PasswordChangeRequest request) {
        service.changePassword(request);
        return ResponseEntity.noContent().build();
    }

    /** Deletes the account and everything it owns, then ends the session. */
    @DeleteMapping
    public ResponseEntity<Void> delete(@Valid @RequestBody DeleteAccountRequest request, HttpServletRequest httpRequest,
                                       HttpServletResponse httpResponse) {
        service.delete(request.password());
        new SecurityContextLogoutHandler().logout(httpRequest, httpResponse, SecurityContextHolder.getContext().getAuthentication());
        return ResponseEntity.noContent().build();
    }
}
