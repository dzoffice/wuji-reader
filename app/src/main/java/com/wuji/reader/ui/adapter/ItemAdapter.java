package com.wuji.reader.ui.adapter;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.wuji.reader.R;
import com.wuji.reader.model.Item;

import java.util.ArrayList;
import java.util.List;

public class ItemAdapter extends RecyclerView.Adapter<ItemAdapter.VH> {

    public interface OnItemClick {
        void onClick(Item item);
    }

    public interface OnItemLongClick {
        void onLongClick(Item item);
    }

    private final List<Item> data = new ArrayList<>();
    private final OnItemClick callback;
    private OnItemLongClick longCallback;

    public ItemAdapter(OnItemClick callback) {
        this.callback = callback;
    }

    public void setOnItemLongClick(OnItemLongClick cb) {
        this.longCallback = cb;
    }

    public void setData(List<Item> list) {
        data.clear();
        if (list != null) {
            data.addAll(list);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_book, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        Item it = data.get(pos);
        Context ctx = h.itemView.getContext();
        h.title.setText(it.title == null ? "" : it.title);

        if (it.sourceName != null && !it.sourceName.isEmpty()) {
            h.meta.setText(it.sourceName);
            h.meta.setVisibility(View.VISIBLE);
        } else {
            h.meta.setVisibility(View.GONE);
        }

        ColorDrawable placeholder = new ColorDrawable(
                ContextCompat.getColor(ctx, R.color.ink_white));
        if (it.cover != null && !it.cover.isEmpty()) {
            Glide.with(ctx).load(it.cover).placeholder(placeholder).into(h.cover);
        } else {
            h.cover.setImageDrawable(placeholder);
        }

        h.itemView.setOnClickListener(v -> {
            if (callback != null) {
                callback.onClick(it);
            }
        });
        h.itemView.setOnLongClickListener(v -> {
            if (longCallback != null) {
                longCallback.onLongClick(it);
                return true;
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView cover;
        TextView title;
        TextView meta;

        VH(@NonNull View itemView) {
            super(itemView);
            cover = itemView.findViewById(R.id.imgCover);
            title = itemView.findViewById(R.id.tvTitle);
            meta = itemView.findViewById(R.id.tvMeta);
        }
    }
}
