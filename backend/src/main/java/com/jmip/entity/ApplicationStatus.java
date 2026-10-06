package com.jmip.entity;

/**
 * Where an application for a saved job stands (V7.2). The pipeline is SAVED, APPLIED,
 * INTERVIEW, OFFER; REJECTED and WITHDRAWN close it. Any move is allowed, so a user can
 * correct a mistake.
 */
public enum ApplicationStatus {
    SAVED,
    APPLIED,
    INTERVIEW,
    OFFER,
    REJECTED,
    WITHDRAWN
}
