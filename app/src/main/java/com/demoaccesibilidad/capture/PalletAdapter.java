package com.demoaccesibilidad.capture;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Adaptador para la lista de Pallets dentro de un Task.
 */
public class PalletAdapter extends RecyclerView.Adapter<PalletAdapter.PalletViewHolder> {

    private List<Pallet> pallets = new ArrayList<>();

    public void setPallets(List<Pallet> newPallets) {
        this.pallets = newPallets != null ? newPallets : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public PalletViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_pallet, parent, false);
        return new PalletViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull PalletViewHolder holder, int position) {
        Pallet p = pallets.get(position);
        holder.bind(p, position + 1);
    }

    @Override
    public int getItemCount() {
        return pallets.size();
    }

    static class PalletViewHolder extends RecyclerView.ViewHolder {
        final TextView txtNumber;
        final TextView txtPalletCode;
        final TextView txtSku;
        final TextView txtQuantity;
        final TextView txtLocation;
        final TextView txtTime;

        PalletViewHolder(@NonNull View v) {
            super(v);
            txtNumber = v.findViewById(R.id.txt_pallet_number);
            txtPalletCode = v.findViewById(R.id.txt_pallet_code);
            txtSku = v.findViewById(R.id.txt_pallet_sku);
            txtQuantity = v.findViewById(R.id.txt_pallet_qty);
            txtLocation = v.findViewById(R.id.txt_pallet_location);
            txtTime = v.findViewById(R.id.txt_pallet_time);
        }

        void bind(Pallet p, int num) {
            txtNumber.setText(String.valueOf(num));
            txtPalletCode.setText(p.palletCode != null ? p.palletCode : "—");
            txtSku.setText(p.sku != null && !p.sku.isEmpty() ? p.sku : "—");
            txtQuantity.setText(p.getQuantityDisplay());

            if (p.location != null && !p.location.isEmpty()) {
                txtLocation.setVisibility(View.VISIBLE);
                txtLocation.setText("📍\u00A0" + p.location);
            } else {
                txtLocation.setVisibility(View.GONE);
            }

            txtTime.setText(p.getTimestampDisplay());
        }
    }
}
