package com.mscope.browser;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.Locale;

/** 模型列表适配器。 */
public class ModelAdapter extends RecyclerView.Adapter<ModelAdapter.VH> {

    public interface OnItemClick {
        void onClick(ModelItem item);
    }

    private final List<ModelItem> data;
    private final OnItemClick callback;

    public ModelAdapter(List<ModelItem> data, OnItemClick callback) {
        this.data = data;
        this.callback = callback;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_model, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        ModelItem m = data.get(position);
        h.title.setText(m.displayName());
        h.owner.setText(m.fullName());

        StringBuilder sb = new StringBuilder();
        if (!m.task.isEmpty()) sb.append(m.task);
        sb.append("  ↓ ").append(formatCount(m.downloads));
        if (m.stars > 0) sb.append("  ★ ").append(formatCount(m.stars));
        if (!m.license.isEmpty()) sb.append("  · ").append(m.license);
        h.meta.setText(sb.toString());

        String d = m.tags.isEmpty() ? m.description : (m.tags + "  " + m.description);
        if (d != null) d = d.replace('\n', ' ').trim();
        if (d == null || d.isEmpty()) {
            h.desc.setVisibility(View.GONE);
        } else {
            h.desc.setVisibility(View.VISIBLE);
            h.desc.setText(d);
        }

        h.itemView.setOnClickListener(v -> callback.onClick(m));
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    public static String formatCount(long n) {
        if (n >= 100000000L) return String.format(Locale.CHINA, "%.1f亿", n / 100000000.0);
        if (n >= 10000L) return String.format(Locale.CHINA, "%.1f万", n / 10000.0);
        return String.valueOf(n);
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView owner;
        final TextView meta;
        final TextView desc;

        VH(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.tvTitle);
            owner = itemView.findViewById(R.id.tvOwner);
            meta = itemView.findViewById(R.id.tvMeta);
            desc = itemView.findViewById(R.id.tvDesc);
        }
    }
}