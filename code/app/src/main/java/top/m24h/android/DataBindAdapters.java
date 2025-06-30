package top.m24h.android;

import android.graphics.drawable.Drawable;
import android.widget.TextView;
import androidx.databinding.BindingAdapter;

public class DataBindAdapters {
    @BindingAdapter("drawableTint")
    public static void drawableTint (TextView view, int color) {
        for (Drawable d:view.getCompoundDrawables()) {
            d.setTint(color);
        }
    }

}
