package com.jmip.repository;

import com.jmip.entity.EmailVerificationCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EmailVerificationCodeRepository extends JpaRepository<EmailVerificationCode, UUID> {
}
