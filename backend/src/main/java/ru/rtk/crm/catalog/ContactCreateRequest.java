package ru.rtk.crm.catalog;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ContactCreateRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 200) String position,
        @Email @Size(max = 320) String email,
        @Size(max = 50) String phone,
        ContactRole role,
        boolean primary
) {
    public ContactCreateRequest(String name, String position, String email, String phone) {
        this(name, position, email, phone, null, false);
    }
}
