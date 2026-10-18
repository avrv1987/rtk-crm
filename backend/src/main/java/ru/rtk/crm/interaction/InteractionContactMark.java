package ru.rtk.crm.interaction;

import java.util.List;
import java.util.UUID;

public record InteractionContactMark(InteractionContactChannel channel, List<UUID> participantIds) {
}
