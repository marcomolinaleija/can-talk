package com.google.android.accessibility.talkback.actor.gemini.ui;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import androidx.core.content.FileProvider;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.dialog.BaseDialog;
import com.google.android.accessibility.talkback.imagecaption.Result;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Shares the original analyzed image and its description through the Android chooser. */
final class CaptionSharing {
  private static final ExecutorService FILE_EXECUTOR = Executors.newSingleThreadExecutor();
  private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

  private CaptionSharing() {}

  /** Matches the description shown in both the simple dialog and the Q&A result page. */
  static String description(Context context, Result... results) {
    List<String> parts = new ArrayList<>();
    for (Result result : results) {
      if (result == null || TextUtils.isEmpty(result.text())) {
        continue;
      }
      parts.add(
          switch (result.type()) {
            case OCR -> context.getString(R.string.detailed_image_description_ocr_result, result.text());
            case ICON_LABEL ->
                context.getString(R.string.detailed_image_description_icon_result, result.text());
            default -> result.text().toString();
          });
    }
    return TextUtils.join("\n\n", parts);
  }

  static void showOptions(
      Context context, byte[] image, String description, Runnable dismissResult) {
    new ShareDialog(context, image, description, dismissResult).showDialog();
  }

  private static final class ShareDialog extends BaseDialog {
    private final byte[] image;
    private final String description;
    private final Runnable dismissResult;
    private Button imageButton;
    private Button descriptionButton;
    private Button bothButton;
    private TextView errorView;
    private boolean active = true;

    ShareDialog(Context context, byte[] image, String description, Runnable dismissResult) {
      super(context, R.string.caption_share_title, null);
      this.image = image;
      this.description = description;
      this.dismissResult = dismissResult;
      setIncludeNegativeButton(false);
      setPositiveButtonStringRes(android.R.string.cancel);
    }

    @Override
    public View getCustomizedView(LayoutInflater inflater) {
      View content = inflater.inflate(R.layout.caption_share_dialog, null);
      imageButton = content.findViewById(R.id.caption_share_image);
      descriptionButton = content.findViewById(R.id.caption_share_description);
      bothButton = content.findViewById(R.id.caption_share_both);
      errorView = content.findViewById(R.id.caption_share_error);
      setBusy(false);
      imageButton.setOnClickListener(view -> share(true, false));
      descriptionButton.setOnClickListener(view -> share(false, true));
      bothButton.setOnClickListener(view -> share(true, true));
      return content;
    }

    private void setBusy(boolean busy) {
      boolean hasImage = image != null && image.length > 0;
      boolean hasDescription = !TextUtils.isEmpty(description);
      imageButton.setEnabled(!busy && hasImage);
      descriptionButton.setEnabled(!busy && hasDescription);
      bothButton.setEnabled(!busy && hasImage && hasDescription);
    }

    private void share(boolean includeImage, boolean includeDescription) {
      errorView.setVisibility(View.GONE);
      setBusy(true);
      Intent send = new Intent(Intent.ACTION_SEND);
      send.setType(includeImage ? "image/jpeg" : "text/plain");
      if (includeDescription) {
        send.putExtra(Intent.EXTRA_TEXT, description);
      }
      if (!includeImage) {
        launchChooser(send);
        return;
      }
      // Do not write screenshots on the UI thread or remove files still being read by a receiver.
      FILE_EXECUTOR.execute(
          () -> {
            try {
              File directory = new File(context.getCacheDir(), "shared_captures");
              if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("Cannot create share directory");
              }
              File[] oldFiles = directory.listFiles();
              long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1);
              if (oldFiles != null) {
                for (File oldFile : oldFiles) {
                  if (oldFile.isFile() && oldFile.lastModified() < cutoff) {
                    oldFile.delete();
                  }
                }
              }
              File file = File.createTempFile("capture-", ".jpg", directory);
              try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(image);
              }
              Uri uri =
                  FileProvider.getUriForFile(
                      context, context.getPackageName() + ".providers.FileProvider", file);
              send.putExtra(Intent.EXTRA_STREAM, uri);
              send.setClipData(ClipData.newRawUri("", uri));
              send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
              MAIN_HANDLER.post(() -> launchChooser(send));
            } catch (IOException | IllegalArgumentException | SecurityException exception) {
              MAIN_HANDLER.post(this::showError);
            }
          });
    }

    private void launchChooser(Intent send) {
      if (!active) {
        return;
      }
      try {
        Intent chooser = Intent.createChooser(send, context.getString(R.string.caption_share_title));
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(chooser);
        dismissDialog();
        // Accessibility overlays must close so the chooser and receiving app can be used.
        dismissResult.run();
      } catch (android.content.ActivityNotFoundException | SecurityException exception) {
        showError();
      }
    }

    private void showError() {
      if (active) {
        setBusy(false);
        errorView.setVisibility(View.VISIBLE);
        errorView.announceForAccessibility(errorView.getText());
      }
    }

    @Override
    public String getMessageString() {
      return null;
    }

    @Override
    public void handleDialogClick(int buttonClicked) {}

    @Override
    public void handleDialogDismiss() {
      active = false;
    }
  }
}
