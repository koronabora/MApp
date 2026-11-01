package local.mapp;

import android.content.Context;
import android.graphics.Matrix;
import android.view.*;
import android.widget.ImageView;

/** Pinch-to-zoom and drag, with double tap to return to the fitted image. */
final class ZoomImage extends ImageView {
    private final Matrix matrix=new Matrix();
    private final ScaleGestureDetector zoom;
    private final GestureDetector taps;
    private float x,y,scale=1;
    ZoomImage(Context context) {
        super(context); setScaleType(ScaleType.MATRIX);
        zoom=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                float next=Math.max(1,Math.min(8,scale*detector.getScaleFactor()));
                matrix.postScale(next/scale,next/scale,detector.getFocusX(),detector.getFocusY()); scale=next; setImageMatrix(matrix); return true;
            }
        });
        taps=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(android.view.MotionEvent e) { return true; }
            @Override public boolean onDoubleTap(android.view.MotionEvent e) { fit(); return true; }
        });
    }
    void fit() {
        if(getDrawable()==null || getWidth()==0) return;
        float w=getDrawable().getIntrinsicWidth(), h=getDrawable().getIntrinsicHeight();
        float factor=Math.min(getWidth()/w,getHeight()/h); matrix.reset(); matrix.postScale(factor,factor);
        matrix.postTranslate((getWidth()-w*factor)/2,(getHeight()-h*factor)/2); scale=1; setImageMatrix(matrix);
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh) { super.onSizeChanged(w,h,oldw,oldh); fit(); }
    @Override public boolean onTouchEvent(MotionEvent event) {
        zoom.onTouchEvent(event); taps.onTouchEvent(event);
        if(event.getActionMasked()==MotionEvent.ACTION_MOVE && !zoom.isInProgress() && event.getPointerCount()==1 && scale>1) {
            matrix.postTranslate(event.getX()-x,event.getY()-y); setImageMatrix(matrix);
        }
        x=event.getX(); y=event.getY(); return true;
    }
}
