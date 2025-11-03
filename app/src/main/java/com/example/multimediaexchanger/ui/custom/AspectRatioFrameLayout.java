package com.example.multimediaexchanger.ui.custom;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * A FrameLayout that maintains a specific aspect ratio.
 */
public class AspectRatioFrameLayout extends FrameLayout {

    private double aspectRatio = 4.0 / 3.0; // Default to 4:3

    public AspectRatioFrameLayout(Context context) {
        super(context);
    }

    public AspectRatioFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setAspectRatio(double ratio) {
        if (this.aspectRatio != ratio) {
            this.aspectRatio = ratio;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (aspectRatio <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        int parentWidth = MeasureSpec.getSize(widthMeasureSpec);
        int parentHeight = MeasureSpec.getSize(heightMeasureSpec);

        int newWidth;
        int newHeight;

        if (parentHeight > 0 && (parentWidth > (int) (parentHeight * aspectRatio))) {
            // Height is the limiting dimension, pillarbox
            newHeight = parentHeight;
            newWidth = (int) (newHeight * aspectRatio);
        } else {
            // Width is the limiting dimension, letterbox
            newWidth = parentWidth;
            newHeight = (int) (newWidth / aspectRatio);
        }

        super.onMeasure(
            MeasureSpec.makeMeasureSpec(newWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(newHeight, MeasureSpec.EXACTLY)
        );
    }
}
