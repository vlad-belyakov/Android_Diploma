package com.example.multimediaexchanger.ui.messages;

import android.net.Uri;

public class Message {
    public enum MessageType {
        TEXT_SENT, TEXT_RECEIVED, IMAGE_SENT, IMAGE_RECEIVED
    }

    // FIXED: Fields are not final to allow Gson to instantiate the class
    private MessageType type;
    private String text;
    private transient Uri imageUri;
    private String imageUriString;
    private long timestamp;

    // FIXED: Added a no-argument constructor for Gson
    public Message() {}

    public Message(MessageType type, String text) {
        this.type = type;
        this.text = text;
        this.timestamp = System.currentTimeMillis();
    }

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

    public String getImageUriString() {
        return imageUriString;
    }

    public void setImageUriString(String imageUriString) {
        this.imageUriString = imageUriString;
    }
    
    public void setImageUri(Uri imageUri) {
        this.imageUri = imageUri;
    }
}
