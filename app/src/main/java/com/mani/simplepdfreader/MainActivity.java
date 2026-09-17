package com.mani.simplepdfreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int OPEN_PDF = 1001;
    private static final String PREFS = "reader_prefs_v2";
    private static final String LAST_URI = "last_uri";
    private static final String RECENTS = "recent_pdfs";
    private static final int MAX_RECENTS = 12;

    private PdfRenderer renderer;
    private PdfRenderer.Page page;
    private ParcelFileDescriptor descriptor;
    private Uri currentUri;
    private String currentName = "No PDF";
    private int pageIndex = 0;

    private LinearLayout root;
    private LinearLayout topRow;
    private LinearLayout toolRow;
    private LinearLayout bottomRow;
    private TextView titleLabel;
    private TextView pageLabel;
    private Button prevButton;
    private Button nextButton;
    private Button shareButton;
    private Button searchButton;
    private Button bookmarkButton;
    private Button themeButton;
    private Button fullButton;
    private PdfView pdfView;

    private SharedPreferences prefs;
    private boolean darkMode;
    private boolean invertPdf;
    private boolean fullscreen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        darkMode = prefs.getBoolean("dark_mode", false);
        invertPdf = prefs.getBoolean("invert_pdf", false);
        PDFBoxResourceLoader.init(getApplicationContext());
        buildUi();
        applyTheme();

        Intent incoming = getIntent();
        if (incoming != null && Intent.ACTION_VIEW.equals(incoming.getAction()) && incoming.getData() != null) {
            openPdf(incoming.getData(), true, true);
            return;
        }

        String last = prefs.getString(LAST_URI, null);
        if (last != null) {
            try {
                openPdf(Uri.parse(last), false, true);
            } catch (Exception ignored) {
            }
        }
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        titleLabel = new TextView(this);
        titleLabel.setText(currentName);
        titleLabel.setTextSize(16f);
        titleLabel.setSingleLine(true);
        titleLabel.setPadding(dp(12), dp(8), dp(12), dp(4));

        topRow = horizontalRow();
        Button openButton = button("Open");
        openButton.setOnClickListener(v -> choosePdf());
        Button recentButton = button("Recent");
        recentButton.setOnClickListener(v -> showRecentPdfs());
        searchButton = button("Search");
        searchButton.setEnabled(false);
        searchButton.setOnClickListener(v -> askSearch());
        shareButton = button("Share");
        shareButton.setEnabled(false);
        shareButton.setOnClickListener(v -> sharePdf());
        addEqual(topRow, openButton, recentButton, searchButton, shareButton);

        toolRow = horizontalRow();
        bookmarkButton = button("Bookmark");
        bookmarkButton.setEnabled(false);
        bookmarkButton.setOnClickListener(v -> toggleBookmark());
        bookmarkButton.setOnLongClickListener(v -> { showBookmarks(); return true; });
        Button jumpButton = button("Jump");
        jumpButton.setOnClickListener(v -> askJumpToPage());
        themeButton = button(darkMode ? "Light" : "Dark");
        themeButton.setOnClickListener(v -> toggleTheme());
        fullButton = button("Full");
        fullButton.setOnClickListener(v -> toggleFullscreen());
        addEqual(toolRow, bookmarkButton, jumpButton, themeButton, fullButton);

        pdfView = new PdfView();

        bottomRow = horizontalRow();
        prevButton = button("Previous");
        prevButton.setEnabled(false);
        prevButton.setOnClickListener(v -> showPage(pageIndex - 1));

        pageLabel = new TextView(this);
        pageLabel.setText("No PDF");
        pageLabel.setGravity(Gravity.CENTER);
        pageLabel.setTextSize(16f);
        pageLabel.setPadding(dp(4), 0, dp(4), 0);
        pageLabel.setOnClickListener(v -> askJumpToPage());
        pageLabel.setOnLongClickListener(v -> { showDocumentInfo(); return true; });

        nextButton = button("Next");
        nextButton.setEnabled(false);
        nextButton.setOnClickListener(v -> showPage(pageIndex + 1));

        bottomRow.addView(prevButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bottomRow.addView(pageLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        bottomRow.addView(nextButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(titleLabel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(topRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(toolRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(pdfView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(bottomRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
    }

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(2), dp(4), dp(2));
        return row;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12f);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setPadding(dp(4), dp(8), dp(4), dp(8));
        return b;
    }

    private void addEqual(LinearLayout row, View... views) {
        for (View v : views) row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void applyTheme() {
        int bg = darkMode ? Color.rgb(18, 18, 18) : Color.rgb(245, 245, 245);
        int panel = darkMode ? Color.rgb(32, 32, 32) : Color.WHITE;
        int text = darkMode ? Color.WHITE : Color.rgb(25, 25, 25);
        int viewer = darkMode ? Color.rgb(12, 12, 12) : Color.rgb(225, 225, 225);
        root.setBackgroundColor(bg);
        topRow.setBackgroundColor(panel);
        toolRow.setBackgroundColor(panel);
        bottomRow.setBackgroundColor(panel);
        titleLabel.setTextColor(text);
        pageLabel.setTextColor(text);
        pdfView.setBackgroundColor(viewer);
        themeButton.setText(darkMode ? "Light" : "Dark");
        pdfView.setInvert(invertPdf && darkMode);
    }

    private void toggleTheme() {
        darkMode = !darkMode;
        prefs.edit().putBoolean("dark_mode", darkMode).apply();
        applyTheme();
    }

    private void toggleFullscreen() {
        fullscreen = !fullscreen;
        Window window = getWindow();
        if (fullscreen) {
            window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
            titleLabel.setVisibility(View.GONE);
            topRow.setVisibility(View.GONE);
            toolRow.setVisibility(View.GONE);
            bottomRow.setVisibility(View.GONE);
            fullButton.setText("Exit");
            Toast.makeText(this, "Tap twice with three fingers is not required; press Back to exit fullscreen", Toast.LENGTH_SHORT).show();
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            titleLabel.setVisibility(View.VISIBLE);
            topRow.setVisibility(View.VISIBLE);
            toolRow.setVisibility(View.VISIBLE);
            bottomRow.setVisibility(View.VISIBLE);
            fullButton.setText("Full");
        }
    }

    @Override
    public void onBackPressed() {
        if (fullscreen) {
            toggleFullscreen();
        } else {
            super.onBackPressed();
        }
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
            openPdf(uri, true, false);
        }
    }

    private void openPdf(Uri uri, boolean saveRecent, boolean restorePage) {
        closePdf();
        try {
            descriptor = getContentResolver().openFileDescriptor(uri, "r");
            if (descriptor == null) throw new Exception("Unable to open file");
            renderer = new PdfRenderer(descriptor);
            currentUri = uri;
            currentName = getDisplayName(uri);
            titleLabel.setText(currentName);
            prefs.edit().putString(LAST_URI, uri.toString()).apply();
            if (saveRecent) addRecent(uri, currentName);

            int target = restorePage ? prefs.getInt(pageKey(uri), 0) : 0;
            if (target < 0 || target >= renderer.getPageCount()) target = 0;
            showPage(target);
            shareButton.setEnabled(true);
            searchButton.setEnabled(true);
            bookmarkButton.setEnabled(true);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open this PDF", Toast.LENGTH_LONG).show();
            removeRecent(uri.toString());
            closePdf();
        }
    }

    private String getDisplayName(Uri uri) {
        String name = "PDF document";
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = cursor.getString(idx);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return name == null ? "PDF document" : name;
    }

    private void showPage(int index) {
        if (renderer == null || index < 0 || index >= renderer.getPageCount()) return;
        try {
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
            prefs.edit().putInt(pageKey(currentUri), index).apply();
            updateBookmarkButton();
        } catch (Exception e) {
            Toast.makeText(this, "Unable to render this page", Toast.LENGTH_SHORT).show();
        }
    }

    private String pageKey(Uri uri) {
        return "last_page_" + (uri == null ? 0 : uri.toString().hashCode());
    }

    private String bookmarkKey() {
        return "bookmarks_" + (currentUri == null ? 0 : currentUri.toString().hashCode());
    }

    private Set<String> getBookmarks() {
        return new HashSet<>(prefs.getStringSet(bookmarkKey(), new HashSet<>()));
    }

    private void toggleBookmark() {
        if (renderer == null) return;
        Set<String> set = getBookmarks();
        String page = String.valueOf(pageIndex);
        boolean added;
        if (set.contains(page)) {
            set.remove(page);
            added = false;
        } else {
            set.add(page);
            added = true;
        }
        prefs.edit().putStringSet(bookmarkKey(), set).apply();
        updateBookmarkButton();
        Toast.makeText(this, added ? "Page bookmarked" : "Bookmark removed", Toast.LENGTH_SHORT).show();
    }

    private void updateBookmarkButton() {
        if (bookmarkButton == null || renderer == null) return;
        bookmarkButton.setText(getBookmarks().contains(String.valueOf(pageIndex)) ? "Bookmarked" : "Bookmark");
    }

    private void showBookmarks() {
        if (renderer == null) return;
        Set<String> set = getBookmarks();
        if (set.isEmpty()) {
            Toast.makeText(this, "No bookmarks in this PDF", Toast.LENGTH_SHORT).show();
            return;
        }
        List<Integer> pages = new ArrayList<>();
        for (String s : set) {
            try { pages.add(Integer.parseInt(s)); } catch (Exception ignored) {}
        }
        java.util.Collections.sort(pages);
        String[] items = new String[pages.size()];
        for (int i = 0; i < pages.size(); i++) items[i] = "Page " + (pages.get(i) + 1);
        new AlertDialog.Builder(this)
                .setTitle("Bookmarks")
                .setItems(items, (d, which) -> showPage(pages.get(which)))
                .setNegativeButton("Close", null)
                .show();
    }

    private void askJumpToPage() {
        if (renderer == null) {
            Toast.makeText(this, "Open a PDF first", Toast.LENGTH_SHORT).show();
            return;
        }
        EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setHint("1 - " + renderer.getPageCount());
        input.setText(String.valueOf(pageIndex + 1));
        input.selectAll();
        new AlertDialog.Builder(this)
                .setTitle("Jump to page")
                .setView(input)
                .setPositiveButton("Go", (dialog, which) -> {
                    try {
                        int p = Integer.parseInt(input.getText().toString().trim()) - 1;
                        if (p >= 0 && p < renderer.getPageCount()) showPage(p);
                        else Toast.makeText(this, "Page number out of range", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(this, "Enter a valid page number", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void askSearch() {
        if (currentUri == null) return;
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Word or phrase");
        new AlertDialog.Builder(this)
                .setTitle("Search inside PDF")
                .setView(input)
                .setPositiveButton("Search", (d, w) -> {
                    String q = input.getText().toString().trim();
                    if (!q.isEmpty()) searchPdf(q);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void searchPdf(String query) {
        Uri uri = currentUri;
        if (uri == null) return;
        ProgressDialog progress = ProgressDialog.show(this, "Searching", "Reading PDF text…", true, false);
        new Thread(() -> {
            List<Integer> matches = new ArrayList<>();
            String error = null;
            try (InputStream in = getContentResolver().openInputStream(uri); PDDocument doc = PDDocument.load(in)) {
                PDFTextStripper stripper = new PDFTextStripper();
                String needle = query.toLowerCase(Locale.ROOT);
                int pages = doc.getNumberOfPages();
                for (int p = 1; p <= pages; p++) {
                    stripper.setStartPage(p);
                    stripper.setEndPage(p);
                    String text = stripper.getText(doc);
                    if (text != null && text.toLowerCase(Locale.ROOT).contains(needle)) matches.add(p - 1);
                }
            } catch (Exception e) {
                error = "Search failed. This PDF may be encrypted, damaged, or image-only.";
            }
            String finalError = error;
            runOnUiThread(() -> {
                if (!isFinishing()) progress.dismiss();
                if (finalError != null) {
                    Toast.makeText(this, finalError, Toast.LENGTH_LONG).show();
                } else if (matches.isEmpty()) {
                    Toast.makeText(this, "No text match found. Scanned PDFs need OCR.", Toast.LENGTH_LONG).show();
                } else {
                    String[] items = new String[matches.size()];
                    for (int i = 0; i < matches.size(); i++) items[i] = "Page " + (matches.get(i) + 1);
                    new AlertDialog.Builder(this)
                            .setTitle("Found on " + matches.size() + " page(s)")
                            .setItems(items, (d, which) -> showPage(matches.get(which)))
                            .setNegativeButton("Close", null)
                            .show();
                }
            });
        }).start();
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

    private void showDocumentInfo() {
        if (renderer == null) return;
        String info = "Name: " + currentName + "\nPages: " + renderer.getPageCount() +
                "\nCurrent page: " + (pageIndex + 1) +
                "\nBookmarks: " + getBookmarks().size() +
                "\n\nTip: Long-press Bookmark to see saved pages.";
        new AlertDialog.Builder(this)
                .setTitle("Document info")
                .setMessage(info)
                .setNeutralButton(invertPdf ? "Normal PDF colors" : "Invert PDF in dark mode", (d, w) -> {
                    invertPdf = !invertPdf;
                    prefs.edit().putBoolean("invert_pdf", invertPdf).apply();
                    pdfView.setInvert(invertPdf && darkMode);
                })
                .setPositiveButton("OK", null)
                .show();
    }

    private void addRecent(Uri uri, String name) {
        try {
            JSONArray old = new JSONArray(prefs.getString(RECENTS, "[]"));
            JSONArray arr = new JSONArray();
            JSONObject now = new JSONObject();
            now.put("uri", uri.toString());
            now.put("name", name);
            arr.put(now);
            for (int i = 0; i < old.length() && arr.length() < MAX_RECENTS; i++) {
                JSONObject obj = old.optJSONObject(i);
                if (obj != null && !uri.toString().equals(obj.optString("uri"))) arr.put(obj);
            }
            prefs.edit().putString(RECENTS, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void removeRecent(String uri) {
        try {
            JSONArray old = new JSONArray(prefs.getString(RECENTS, "[]"));
            JSONArray arr = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONObject obj = old.optJSONObject(i);
                if (obj != null && !uri.equals(obj.optString("uri"))) arr.put(obj);
            }
            prefs.edit().putString(RECENTS, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void showRecentPdfs() {
        try {
            JSONArray arr = new JSONArray(prefs.getString(RECENTS, "[]"));
            if (arr.length() == 0) {
                Toast.makeText(this, "No recent PDFs yet", Toast.LENGTH_SHORT).show();
                return;
            }
            String[] names = new String[arr.length()];
            String[] uris = new String[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                names[i] = obj.optString("name", "PDF document");
                uris[i] = obj.optString("uri", "");
            }
            new AlertDialog.Builder(this)
                    .setTitle("Recent PDFs")
                    .setItems(names, (d, which) -> openPdf(Uri.parse(uris[which]), false, true))
                    .setNeutralButton("Clear recents", (d, w) -> prefs.edit().remove(RECENTS).apply())
                    .setNegativeButton("Close", null)
                    .show();
        } catch (Exception e) {
            prefs.edit().remove(RECENTS).apply();
        }
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
        private Bitmap bitmap;
        private float scale = 1f;
        private float offsetX = 0f;
        private float offsetY = 0f;
        private boolean inverted = false;
        private final ScaleGestureDetector scaleDetector;
        private final GestureDetector gestureDetector;

        PdfView() {
            super(MainActivity.this);
            scaleDetector = new ScaleGestureDetector(MainActivity.this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    scale = Math.max(1f, Math.min(scale * detector.getScaleFactor(), 5f));
                    clampOffsets();
                    invalidate();
                    return true;
                }
            });

            gestureDetector = new GestureDetector(MainActivity.this, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { return true; }

                @Override
                public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                    if (scale > 1f) {
                        offsetX -= distanceX;
                        offsetY -= distanceY;
                        clampOffsets();
                        invalidate();
                    }
                    return true;
                }

                @Override
                public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                    if (scale <= 1.05f && e1 != null && e2 != null) {
                        float dx = e2.getX() - e1.getX();
                        if (Math.abs(dx) > dp(60)) {
                            if (dx < 0) showPage(pageIndex + 1);
                            else showPage(pageIndex - 1);
                            return true;
                        }
                    }
                    return false;
                }

                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    if (scale > 1.05f) {
                        scale = 1f; offsetX = 0f; offsetY = 0f;
                    } else {
                        scale = 2.2f;
                    }
                    clampOffsets();
                    invalidate();
                    return true;
                }

                @Override
                public boolean onSingleTapConfirmed(MotionEvent e) {
                    if (fullscreen) toggleFullscreen();
                    return true;
                }
            });
        }

        void setBitmap(Bitmap b) {
            if (bitmap != null && bitmap != b && !bitmap.isRecycled()) bitmap.recycle();
            bitmap = b;
            scale = 1f;
            offsetX = 0f;
            offsetY = 0f;
            invalidate();
        }

        void setInvert(boolean value) {
            inverted = value;
            if (inverted) {
                ColorMatrix cm = new ColorMatrix(new float[] {
                        -1, 0, 0, 0, 255,
                        0, -1, 0, 0, 255,
                        0, 0, -1, 0, 255,
                        0, 0, 0, 1, 0
                });
                paint.setColorFilter(new ColorMatrixColorFilter(cm));
            } else {
                paint.setColorFilter(null);
            }
            invalidate();
        }

        private void clampOffsets() {
            if (bitmap == null) return;
            float scaledW = bitmap.getWidth() * scale;
            float scaledH = bitmap.getHeight() * scale;
            float maxX = Math.max(0, (scaledW - getWidth()) / 2f);
            float maxY = Math.max(0, (scaledH - getHeight()) / 2f);
            offsetX = Math.max(-maxX, Math.min(maxX, offsetX));
            offsetY = Math.max(-maxY, Math.min(maxY, offsetY));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (bitmap == null) return;
            float baseScale = Math.min((float) getWidth() / bitmap.getWidth(), (float) getHeight() / bitmap.getHeight());
            float drawScale = baseScale * scale;
            float left = (getWidth() - bitmap.getWidth() * drawScale) / 2f + offsetX;
            float top = (getHeight() - bitmap.getHeight() * drawScale) / 2f + offsetY;
            canvas.save();
            canvas.translate(left, top);
            canvas.scale(drawScale, drawScale);
            canvas.drawBitmap(bitmap, 0, 0, paint);
            canvas.restore();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            scaleDetector.onTouchEvent(event);
            gestureDetector.onTouchEvent(event);
            return true;
        }
    }
}
