package com.android.pipeliner;

import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class PipelineAdapter extends RecyclerView.Adapter<PipelineAdapter.ViewHolder> {

    public interface OnPipelineClickListener {
        void onPipelineClick(PipelineItem item);
        void onPipelineDelete(PipelineItem item);
        void onPipelineEdit(PipelineItem item);
        void onPipelineFavorite(PipelineItem item);
        void onPipelineShare(PipelineItem item);
    }

    private List<PipelineItem> pipelines;
    private OnPipelineClickListener listener;
    private Context context;

    public PipelineAdapter(Context context, List<PipelineItem> pipelines, OnPipelineClickListener listener) {
        this.context = context;
        this.pipelines = pipelines;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_pipeline, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        PipelineItem item = pipelines.get(position);
        
        holder.name.setText(item.getName());
        holder.preview.setText(item.getPipeline());
        
        // Category badge
        holder.categoryBadge.setText(item.getCategory().toUpperCase());
        holder.categoryBadge.setBackgroundColor(item.getCategoryColor());
        
        // Category indicator stripe
        holder.categoryIndicator.setBackgroundColor(item.getCategoryColor());
        

        // Favorite icon with animation
        if (item.isFavorite()) {
            holder.favoriteIcon.setImageResource(R.drawable.bookmark_fill_24dp);
            holder.favoriteIcon.setColorFilter(0xFFFFD700); // Gold color
        } else {
            holder.favoriteIcon.setImageResource(R.drawable.bookmark_24dp);
            holder.favoriteIcon.setColorFilter(0xFF9E9E9E); // Grey
        }
        
        // Click to play
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onPipelineClick(item);
            }
        });
        
        // Favorite toggle
        holder.favoriteIcon.setOnClickListener(v -> {
            if (listener != null) {
                listener.onPipelineFavorite(item);
            }
        });
        
        // Share button
        holder.shareButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onPipelineShare(item);
            }
        });
        
        // More options menu
        holder.moreButton.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(context, holder.moreButton);
            popup.inflate(R.menu.pipeline_item_menu);
            popup.setOnMenuItemClickListener(menuItem -> {
                int id = menuItem.getItemId();
                if (id == R.id.action_edit) {
                    showEditDialog(item);
                    return true;
                } else if (id == R.id.action_delete) {
                    if (listener != null) {
                        listener.onPipelineDelete(item);
                    }
                    return true;
                }
                return false;
            });
            popup.show();
        });
    }

    @Override
    public int getItemCount() {
        return pipelines.size();
    }

    public void updatePipelines(List<PipelineItem> newPipelines) {
        this.pipelines = newPipelines;
        notifyDataSetChanged();
    }

    private void showEditDialog(PipelineItem item) {
        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_pipeline_edit, null);
        EditText nameInput = dialogView.findViewById(R.id.pipelineNameInput);
        EditText pipelineInput = dialogView.findViewById(R.id.pipelineInput);
        
        nameInput.setText(item.getName());
        pipelineInput.setText(item.getPipeline());
        
        new AlertDialog.Builder(context)
            .setTitle("Edit Pipeline")
            .setView(dialogView)
            .setPositiveButton("Save", (dialog, which) -> {
                item.setName(nameInput.getText().toString());
                item.setPipeline(pipelineInput.getText().toString());
                if (listener != null) {
                    listener.onPipelineEdit(item);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void duplicatePipeline(PipelineItem item) {
        PipelineItem duplicate = new PipelineItem(
            item.getName() + " (Copy)",
            item.getPipeline()
        );
        PipelineStorage storage = new PipelineStorage(context);
        storage.addPipeline(duplicate);
        
        // Refresh the list
        pipelines = storage.getSortedPipelines("recent");
        notifyDataSetChanged();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView name;
        TextView preview;
        TextView date;
        TextView categoryBadge;
        View categoryIndicator;
        ImageView favoriteIcon;
        ImageButton moreButton;
        ImageButton shareButton;

        ViewHolder(View view) {
            super(view);
            name = view.findViewById(R.id.pipelineName);
            preview = view.findViewById(R.id.pipelinePreview);
            categoryBadge = view.findViewById(R.id.categoryBadge);
            categoryIndicator = view.findViewById(R.id.categoryIndicator);
            favoriteIcon = view.findViewById(R.id.favoriteIcon);
            moreButton = view.findViewById(R.id.moreOptions);
            shareButton = view.findViewById(R.id.shareButton);
        }
    }
}
