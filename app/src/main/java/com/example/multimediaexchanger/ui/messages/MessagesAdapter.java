package com.example.multimediaexchanger.ui.messages;

import android.content.Context;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.bumptech.glide.Glide;
import com.example.multimediaexchanger.R;
import com.example.multimediaexchanger.databinding.ItemImageReceivedBinding;
import com.example.multimediaexchanger.databinding.ItemImageSentBinding;
import com.example.multimediaexchanger.databinding.ItemMessageReceivedBinding;
import com.example.multimediaexchanger.databinding.ItemMessageSentBinding;

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

    public void updateMessages(List<Message> newMessages) {
        if (newMessages == null) return;
        this.messageList = newMessages;
        // It's better to use DiffUtil for more efficient updates,
        // but for simplicity, we'll use notifyDataSetChanged for now.
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
            default: return -1; // Should not happen
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(context);
        switch (viewType) {
            case VIEW_TYPE_TEXT_SENT:
                return new TextSentViewHolder(ItemMessageSentBinding.inflate(inflater, parent, false));
            case VIEW_TYPE_TEXT_RECEIVED:
                return new TextReceivedViewHolder(ItemMessageReceivedBinding.inflate(inflater, parent, false));
            case VIEW_TYPE_IMAGE_SENT:
                return new ImageSentViewHolder(ItemImageSentBinding.inflate(inflater, parent, false));
            case VIEW_TYPE_IMAGE_RECEIVED:
                return new ImageReceivedViewHolder(ItemImageReceivedBinding.inflate(inflater, parent, false));
            default: throw new IllegalArgumentException("Invalid view type: " + viewType);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Message message = messageList.get(position);
        switch (holder.getItemViewType()) {
            case VIEW_TYPE_TEXT_SENT:
                ((TextSentViewHolder) holder).bind(message);
                break;
            case VIEW_TYPE_TEXT_RECEIVED:
                ((TextReceivedViewHolder) holder).bind(message);
                break;
            case VIEW_TYPE_IMAGE_SENT:
                ((ImageSentViewHolder) holder).bind(message);
                break;
            case VIEW_TYPE_IMAGE_RECEIVED:
                ((ImageReceivedViewHolder) holder).bind(message);
                break;
        }
    }

    @Override
    public int getItemCount() {
        return messageList.size();
    }

    // ViewHolder for sent text messages
    private static class TextSentViewHolder extends RecyclerView.ViewHolder {
        private final ItemMessageSentBinding binding;
        TextSentViewHolder(ItemMessageSentBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
        void bind(Message message) {
            binding.messageText.setText(message.getText());
        }
    }

    // ViewHolder for received text messages
    private static class TextReceivedViewHolder extends RecyclerView.ViewHolder {
        private final ItemMessageReceivedBinding binding;
        TextReceivedViewHolder(ItemMessageReceivedBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
        void bind(Message message) {
            binding.messageTextView.setText(message.getText());
        }
    }

    // ViewHolder for sent images
    private class ImageSentViewHolder extends RecyclerView.ViewHolder {
        private final ItemImageSentBinding binding;
        ImageSentViewHolder(ItemImageSentBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
        void bind(Message message) {
            Glide.with(context)
                 .load(message.getImageUri())
                 .error(R.drawable.ic_broken_image)
                 .into(binding.imageView);
        }
    }

    // ViewHolder for received images
    private class ImageReceivedViewHolder extends RecyclerView.ViewHolder {
        private final ItemImageReceivedBinding binding;
        ImageReceivedViewHolder(ItemImageReceivedBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
        void bind(Message message) {
            Glide.with(context)
                 .load(message.getImageUri())
                 .error(R.drawable.ic_broken_image)
                 .into(binding.imageView);
        }
    }
}
