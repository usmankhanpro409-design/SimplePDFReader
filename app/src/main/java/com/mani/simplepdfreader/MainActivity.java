package com.mani.simplepdfreader;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;

public class MainActivity extends Activity {
    private static final int OPEN_PDF = 1001;
    private static final String PREFS = "reader_prefs";
    private static final String LAST_URI = "last_uri";

    private PdfRenderer renderer;
    private PdfRenderer.Page page;
    private ParcelFileDescriptor descriptor;
    private Uri currentUri;
    private int pageIndex = 0;

    private PdfView pdfView;
    private TextView pageLabel;
    private Button prevButton;
    private Button nextButton;
    private Button shareButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        Intent incoming = getIntent();
        if (incoming != null && Intent.ACTION_VIEW.equals(incoming.getAction()) && incoming.getData() != null) {
            openPdf(incoming.getData(), true);
            return;
        }

        String last = getSharedPreferences(PREFS, MODE_PRIVATE).getString(LAST_URI, null);
        if (last != null) {
            try {
                openPdf(Uri.parse(last), false);
            } catch (Exception ignored) {
            }
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(245, 245, 245));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(8), dp(8), dp(8));
        top.setBackgroundColor(Color.WHITE);

        Button openButton = new Button(this);
        openButton.setText("Open PDF");
        openButton.setOnClickListener(v -> choosePdf());

        shareButton = new Button(this);
        shareButton.setText("Share");
        shareButton.setEnabled(false);
        shareButton.setOnClickListener(v -> sharePdf());

        top.addView(openButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(shareButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        pdfView = new PdfView();
        pdfView.setBackgroundColor(Color.rgb(230, 230, 230));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(dp(8), dp(6), dp(8), dp(6));
        bottom.setBackgroundColor(Color.WHITE);

        prevButton = new Button(this);
        prevButton.setText("Previous");
        prevButton.setEnabled(false);
        prevButton.setOnClickListener(v -> showPage(pageIndex - 1));

        pageLabel = new TextView(this);
        pageLabel.setText("No PDF");
        pageLabel.setGravity(Gravity.CENTER);
        pageLabel.setTextSize(16f);

        nextButton = new Button(this);
        nextButton.setText("Next");
        nextButton.setEnabled(false);
        nextButton.setOnClickListener(v -> showPage(pageIndex + 1));

        bottom.addView(prevButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bottom.addView(pageLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bottom.addView(nextButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(pdfView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(bottom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void choosePdf() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/pdf");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, OPEN_PDF);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OPEN_PDF && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            openPdf(uri, true);
        }
    }

    private void openPdf(Uri uri, boolean save) {
        closePdf();
        try {
            descriptor = getContentResolver().openFileDescriptor(uri, "r");
            if (descriptor == null) throw new IOException("Unable to open file");
            renderer = new PdfRenderer(descriptor);
            currentUri = uri;
            pageIndex = 0;
            if (save) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(LAST_URI, uri.toString()).apply();
            }
            showPage(0);
            shareButton.setEnabled(true);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open this PDF", Toast.LENGTH_LONG).show();
            closePdf();
        }
    }

    private void showPage(int index) {
        if (renderer == null || index < 0 || index >= renderer.getPageCount()) return;
        if (page != null) page.close();
        page = renderer.openPage(index);
        pageIndex = index;

        int width = Math.max(1, pdfView.getWidth());
        if (width <= 1) width = getResources().getDisplayMetrics().widthPixels;
        float ratio = (float) page.getHeight() / (float) page.getWidth();
        int height = Math.max(1, Math.round(width * ratio));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE);
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        pdfView.setBitmap(bitmap);

        int count = renderer.getPageCount();
        pageLabel.setText((index + 1) + " / " + count);
        prevButton.setEnabled(index > 0);
        nextButton.setEnabled(index < count - 1);
    }

    private void sharePdf() {
        if (currentUri == null) return;
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("application/pdf");
        share.putExtra(Intent.EXTRA_STREAM, currentUri);
        share.setClipData(ClipData.newRawUri("PDF", currentUri));
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, "Share PDF"));
    }

    private void closePdf() {
        try { if (page != null) page.close(); } catch (Exception ignored) {}
        try { if (renderer != null) renderer.close(); } catch (Exception ignored) {}
        try { if (descriptor != null) descriptor.close(); } catch (Exception ignored) {}
        page = null;
        renderer = null;
        descriptor = null;
        currentUri = null;
        if (pdfView != null) pdfView.setBitmap(null);
    }

    @Override
    protected void onDestroy() {
        closePdf();
        super.onDestroy();
    }

    private class PdfView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Matrix matrix = new Matrix();
        private Bitmap bitmap;
        private float scale = 1f;
        private float offsetX = 0f;
        private float offsetY = 0f;
        private final ScaleGestureDetector scaleDetector;
        private final GestureDetector gestureDetector;

        PdfView() {
            super(MainActivity.this);
            setFocusable(true);
            scaleDetector = new ScaleGestureDetector(MainActivity.this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    float newScale = scale * detector.getScaleFactor();
                    scale = Math.max(1f, Math.min(newScale, 4f));
                    invalidate();
                    return true;
                }
            });

            gestureDetector = new GestureDetector(MainActivity.this, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(MotionEvent e) { return true; }

                @Override
                public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                    if (scale > 1f) {
                        offsetX -= distanceX;
                        offsetY -= distanceY;
                        invalidate();
                    }
                    return true;
                }

                @Override
                public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                    if (scale <= 1.05f && e1 != null && e2 != null) {
                        float dx = e2.getX() - e1.getX();
                        if (Math.abs(dx) > dp(70)) {
                            if (dx < 0) showPage(pageIndex + 1);
                            else showPage(pageIndex - 1);
                            return true;
                        }
                    }
                    return false;
                }

                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    scale = scale > 1f ? 1f : 2f;
                    offsetX = 0f;
                    offsetY = 0f;
                    invalidate();
                    return true;
                }
            });
        }

        void setBitmap(Bitmap newBitmap) {
            if (bitmap != null && bitmap != newBitmap) bitmap.recycle();
            bitmap = newBitmap;
            scale = 1f;
            offsetX = 0f;
            offsetY = 0f;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (bitmap == null) return;

            float fitScale = Math.min((float) getWidth() / bitmap.getWidth(), (float) getHeight() / bitmap.getHeight());
            float finalScale = fitScale * scale;
            float drawW = bitmap.getWidth() * finalScale;
            float drawH = bitmap.getHeight() * finalScale;
            float left = (getWidth() - drawW) / 2f + offsetX;
            float top = (getHeight() - drawH) / 2f + offsetY;

            matrix.reset();
            matrix.postScale(finalScale, finalScale);
            matrix.postTranslate(left, top);
            canvas.drawBitmap(bitmap, matrix, paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            scaleDetector.onTouchEvent(event);
            gestureDetector.onTouchEvent(event);
            return true;
        }
    }
}
