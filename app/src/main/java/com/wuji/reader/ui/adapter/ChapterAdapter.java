package com.wuji.reader.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.wuji.reader.R;
import com.wuji.reader.model.Chapter;

import java.util.ArrayList;
import java.util.List;

public class ChapterAdapter extends RecyclerView.Adapter<ChapterAdapter.VH> {

    public interface OnChapterClick {
        void onClick(int index, Chapter chapter);
    }

    private final List<Chapter> data = new ArrayList<>();
    private final OnChapterClick callback;
    /** 当前选中章（目录跳转 / 点击后高亮，黑白反色） */
    private int selected = -1;

    public ChapterAdapter(OnChapterClick callback) {
        this.callback = callback;
    }

    public void setData(List<Chapter> list) {
        data.clear();
        if (list != null) {
            data.addAll(list);
        }
        selected = -1;
        notifyDataSetChanged();
    }

    /** 设置选中章；只刷新新旧两行，避免整列表 notify */
    public void setSelected(int pos) {
        if (selected == pos) {
            return;
        }
        int old = selected;
        selected = pos;
        if (old >= 0 && old < data.size()) {
            notifyItemChanged(old);
        }
        if (pos >= 0 && pos < data.size()) {
            notifyItemChanged(pos);
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_chapter, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        Chapter c = data.get(pos);
        boolean sel = pos == selected;
        h.title.setText((c.title == null ? "" : c.title) + (sel ? "  ← 当前" : ""));
        h.itemView.setBackgroundColor(ContextCompat.getColor(h.itemView.getContext(),
                sel ? R.color.ink_black : R.color.ink_white));
        h.title.setTextColor(ContextCompat.getColor(h.itemView.getContext(),
                sel ? R.color.ink_white : R.color.ink_black));
        h.itemView.setOnClickListener(v -> {
            setSelected(pos);
            if (callback != null) {
                callback.onClick(pos, c);
            }
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView title;

        VH(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.tvChapter);
        }
    }
}
