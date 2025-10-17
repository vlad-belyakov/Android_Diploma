package com.example.multimediaexchanger.ui.messages;

import android.net.Uri;

public class Message {
    public enum MessageType {
        TEXT_SENT, TEXT_RECEIVED, IMAGE_SENT, IMAGE_RECEIVED
    }

    private final MessageType type;
    private String text;
    private Uri imageUri;
    private final long timestamp;

    // Constructor for text messages
    public Message(MessageType type, String text) {
        this.type = type;
        this.text = text;
        this.timestamp = System.currentTimeMillis();
    }

    // Constructor for image messages
    public Message(MessageType type, Uri imageUri) {
        this.type = type;
        this.imageUri = imageUri;
        this.timestamp = System.currentTimeMillis();
    }

    public MessageType getType() {
        return type;
    }

    public String getText() {
        return text;
    }

    public Uri getImageUri() {
        return imageUri;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
