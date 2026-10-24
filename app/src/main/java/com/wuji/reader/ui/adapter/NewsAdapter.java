package com.wuji.reader.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.wuji.reader.R;
import com.wuji.reader.news.HotItem;
import com.wuji.reader.news.TimeFmt;

import java.util.ArrayList;
import java.util.List;

/**
 * 新闻列表适配器：纯文本条目。
 *
 * 因墨水屏与防盗链考虑，暂不上封面（多数站点防盗链，wuji 里靠 images.weserv.nl
 * 代理绕，墨水屏也看不出好坏）——稳且快，展示标题 + 时间 + 热度 / 作者。
 */
public class NewsAdapter extends RecyclerView.Adapter<NewsAdapter.VH> {

    public interface OnItemClick {
        void onClick(HotItem item);
    }

    private final List<HotItem> data = new ArrayList<>();
    private final OnItemClick callback;

    public NewsAdapter(OnItemClick callback) {
        this.callback = callback;
    }

    public void setData(List<HotItem> list) {
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
                .inflate(R.layout.item_news, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        HotItem it = data.get(pos);
        h.title.setText(it.title == null ? "" : it.title);
        h.meta.setText(metaOf(it));
        h.itemView.setOnClickListener(v -> {
            if (callback != null) {
                callback.onClick(it);
            }
        });
    }

    /**
     * 元信息：时间 · 热度 · 作者；缺的字段不占位。
     * 时间取上游 timestamp（毫秒 epoch），解析失败整段省略。
     */
    private static String metaOf(HotItem it) {
        StringBuilder sb = new StringBuilder();
        String time = TimeFmt.shortOf(it.timestamp);
        if (!time.isEmpty()) {
            sb.append(time);
        }
        if (it.hot != null && !it.hot.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append("热度 ").append(it.hot);
        }
        if (it.author != null && !it.author.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(it.author);
        }
        return (sb.length() == 0) ? "—" : sb.toString();
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView title;
        TextView meta;

        VH(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.tvTitle);
            meta = itemView.findViewById(R.id.tvMeta);
        }
    }
}
