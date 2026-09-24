package com.jmip.entity;

import com.jmip.service.resume.ResumeCipher;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Encrypts a text column on its way to the database and decrypts it on the way back, so the
 * entity only ever holds plain text and the table only ever holds ciphertext. Hibernate gets
 * this from Spring, which supplies the cipher.
 */
@Converter
public class EncryptedTextConverter implements AttributeConverter<String, String> {

    private final ResumeCipher cipher;

    public EncryptedTextConverter(ResumeCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return cipher.sealText(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return cipher.openText(dbData);
    }
}
