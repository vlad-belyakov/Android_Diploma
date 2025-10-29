package com.example.multimediaexchanger.ui.messages;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.multimediaexchanger.R;

import java.util.List;

public class MessagesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_TEXT_SENT = 1;
    private static final int VIEW_TYPE_TEXT_RECEIVED = 2;
    private static final int VIEW_TYPE_IMAGE_SENT = 3;
    private static final int VIEW_TYPE_IMAGE_RECEIVED = 4;

    private final Context context;
    private List<Message> messageList;

    public MessagesAdapter(Context context, List<Message> messageList) {
        this.context = context;
        this.messageList = messageList;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void updateMessages(List<Message> newMessages) {
        if (newMessages == null) return;
        this.messageList = newMessages;
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        Message message = messageList.get(position);
        switch (message.getType()) {
            case TEXT_SENT: return VIEW_TYPE_TEXT_SENT;
            case TEXT_RECEIVED: return VIEW_TYPE_TEXT_RECEIVED;
            case IMAGE_SENT: return VIEW_TYPE_IMAGE_SENT;
            case IMAGE_RECEIVED: return VIEW_TYPE_IMAGE_RECEIVED;
            default: return -1;
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view;
        switch (viewType) {
            case VIEW_TYPE_TEXT_SENT:
                view = LayoutInflater.from(context).inflate(R.layout.item_message_sent, parent, false);
                return new TextMessageViewHolder(view);
            case VIEW_TYPE_TEXT_RECEIVED:
                view = LayoutInflater.from(context).inflate(R.layout.item_message_received, parent, false);
                return new TextMessageViewHolder(view);
            case VIEW_TYPE_IMAGE_SENT:
                view = LayoutInflater.from(context).inflate(R.layout.item_image_sent, parent, false);
                return new ImageMessageViewHolder(view);
            case VIEW_TYPE_IMAGE_RECEIVED:
                view = LayoutInflater.from(context).inflate(R.layout.item_image_received, parent, false);
                return new ImageMessageViewHolder(view);
            default: throw new IllegalArgumentException("Invalid view type");
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Message message = messageList.get(position);
        switch (holder.getItemViewType()) {
            case VIEW_TYPE_TEXT_SENT:
            case VIEW_TYPE_TEXT_RECEIVED:
                // --- FINAL, ROBUST FIX FOR TEXT MESSAGES ---
                // This prevents crashes if a message from history has null text.
                TextMessageViewHolder textHolder = (TextMessageViewHolder) holder;
                if (message.getText() != null) {
                    textHolder.messageTextView.setText(message.getText());
                } else {
                    textHolder.messageTextView.setText(""); // Set empty string to prevent crash
                }
                break;
            case VIEW_TYPE_IMAGE_SENT:
            case VIEW_TYPE_IMAGE_RECEIVED:
                ImageMessageViewHolder imageHolder = (ImageMessageViewHolder) holder;
                try {
                    Uri imageUri = message.getImageUri();
                    if (imageUri == null && message.getImageUriString() != null) {
                        imageUri = Uri.parse(message.getImageUriString());
                    }

                    if (imageUri != null) {
                        Glide.with(context)
                            .load(imageUri)
                            .error(R.drawable.ic_broken_image)
                            .into(imageHolder.imageView);
                    } else {
                        imageHolder.imageView.setImageResource(R.drawable.ic_broken_image);
                    }
                } catch (Exception e) {
                    imageHolder.imageView.setImageResource(R.drawable.ic_broken_image);
                }
                break;
        }
    }

    @Override
    public int getItemCount() {
        return messageList.size();
    }

    public static class TextMessageViewHolder extends RecyclerView.ViewHolder {
        TextView messageTextView;
        public TextMessageViewHolder(@NonNull View itemView) {
            super(itemView);
            messageTextView = itemView.findViewById(R.id.messageTextView);
        }
    }

    public static class ImageMessageViewHolder extends RecyclerView.ViewHolder {
        ImageView imageView;
        public ImageMessageViewHolder(@NonNull View itemView) {
            super(itemView);
            imageView = itemView.findViewById(R.id.imageView);
        }
    }
}
