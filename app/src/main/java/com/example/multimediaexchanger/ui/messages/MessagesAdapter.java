package com.example.multimediaexchanger.ui.messages;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.multimediaexchanger.R;
import com.example.multimediaexchanger.databinding.ItemImageReceivedBinding;
import com.example.multimediaexchanger.databinding.ItemImageSentBinding;
import com.example.multimediaexchanger.databinding.ItemMessageReceivedBinding;
import com.example.multimediaexchanger.databinding.ItemMessageSentBinding;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MessagesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private final List<Message> messages;
    private final Context context;
    private final SimpleDateFormat timeFormatter;

    public MessagesAdapter(Context context, List<Message> messages) {
        this.context = context;
        this.messages = messages;
        this.timeFormatter = new SimpleDateFormat("HH:mm", Locale.getDefault());
    }

    @Override
    public int getItemViewType(int position) {
        return messages.get(position).getType().ordinal();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == Message.MessageType.TEXT_SENT.ordinal()) {
            return new SentTextViewHolder(ItemMessageSentBinding.inflate(inflater, parent, false));
        } else if (viewType == Message.MessageType.TEXT_RECEIVED.ordinal()) {
            return new ReceivedTextViewHolder(ItemMessageReceivedBinding.inflate(inflater, parent, false));
        } else if (viewType == Message.MessageType.IMAGE_SENT.ordinal()) {
            return new SentImageViewHolder(ItemImageSentBinding.inflate(inflater, parent, false));
        } else if (viewType == Message.MessageType.IMAGE_RECEIVED.ordinal()) {
            return new ReceivedImageViewHolder(ItemImageReceivedBinding.inflate(inflater, parent, false));
        }
        throw new RuntimeException("Unknown view type: " + viewType);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Message message = messages.get(position);
        switch (message.getType()) {
            case TEXT_SENT:
                ((SentTextViewHolder) holder).bind(message, timeFormatter);
                break;
            case TEXT_RECEIVED:
                ((ReceivedTextViewHolder) holder).bind(message, timeFormatter);
                break;
            case IMAGE_SENT:
                ((SentImageViewHolder) holder).bind(message, timeFormatter);
                break;
            case IMAGE_RECEIVED:
                ((ReceivedImageViewHolder) holder).bind(message, timeFormatter);
                break;
        }
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    // ViewHolder for sent text messages
    static class SentTextViewHolder extends RecyclerView.ViewHolder {
        private final ItemMessageSentBinding binding;
        SentTextViewHolder(ItemMessageSentBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
        void bind(Message message, SimpleDateFormat formatter) {
            binding.messageText.setText(message.getText());
            binding.messageTimestamp.setText(formatter.format(new Date(message.getTimestamp())));
        }
    }

    // ViewHolder for received text messages
    static class ReceivedTextViewHolder extends RecyclerView.ViewHolder {
        private final ItemMessageReceivedBinding binding;
        ReceivedTextViewHolder(ItemMessageReceivedBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
        void bind(Message message, SimpleDateFormat formatter) {
            binding.messageText.setText(message.getText());
            binding.messageTimestamp.setText(formatter.format(new Date(message.getTimestamp())));
        }
    }

    // ViewHolder for sent image messages
    static class SentImageViewHolder extends RecyclerView.ViewHolder {
        private final ItemImageSentBinding binding;
        SentImageViewHolder(ItemImageSentBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
        void bind(Message message, SimpleDateFormat formatter) {
            binding.imageView.setImageURI(message.getImageUri());
            binding.messageTimestamp.setText(formatter.format(new Date(message.getTimestamp())));
        }
    }

    // ViewHolder for received image messages
    static class ReceivedImageViewHolder extends RecyclerView.ViewHolder {
        private final ItemImageReceivedBinding binding;
        ReceivedImageViewHolder(ItemImageReceivedBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
        void bind(Message message, SimpleDateFormat formatter) {
            binding.imageView.setImageURI(message.getImageUri());
            binding.messageTimestamp.setText(formatter.format(new Date(message.getTimestamp())));
        }
    }
}
