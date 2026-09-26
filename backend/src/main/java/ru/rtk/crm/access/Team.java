package ru.rtk.crm.access;

import java.util.UUID;

public record Team(UUID id, String name, int version) {
}
