package dev.fulfillmenthub.runtime.outbox;
public interface OutboxHandler { String type(); void handle(OutboxRow message) throws Exception; }
