/**
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.reactnativecommunity.cameraroll;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.MediaStore.Images;
import android.text.TextUtils;
import android.media.ExifInterface;

import com.facebook.common.logging.FLog;
import com.facebook.react.bridge.GuardedAsyncTask;
import com.facebook.react.bridge.NativeModule;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeArray;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.common.ReactConstants;
import com.facebook.react.module.annotations.ReactModule;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.text.SimpleDateFormat;
import java.text.ParseException;
import java.util.Date;

import javax.annotation.Nullable;

/**
 * {@link NativeModule} that allows JS to interact with the photos and videos on the device (i.e.
 * {@link MediaStore.Images}).
 */
@ReactModule(name = CameraRollModule.NAME)
public class CameraRollModule extends ReactContextBaseJavaModule {

  public static final String NAME = "RNCCameraRoll";

  private static final String ERROR_UNABLE_TO_LOAD = "E_UNABLE_TO_LOAD";
  private static final String ERROR_UNABLE_TO_LOAD_PERMISSION = "E_UNABLE_TO_LOAD_PERMISSION";
  private static final String ERROR_UNABLE_TO_SAVE = "E_UNABLE_TO_SAVE";
  private static final String ERROR_UNABLE_TO_DELETE = "E_UNABLE_TO_DELETE";
  private static final String ERROR_UNABLE_TO_FILTER = "E_UNABLE_TO_FILTER";

  private static final String ASSET_TYPE_PHOTOS = "Photos";
  private static final String ASSET_TYPE_VIDEOS = "Videos";
  private static final String ASSET_TYPE_ALL = "All";

  private static final String INCLUDE_FILENAME = "filename";
  private static final String INCLUDE_FILE_SIZE = "fileSize";
  private static final String INCLUDE_LOCATION = "location";
  private static final String INCLUDE_IMAGE_SIZE = "imageSize";
  private static final String INCLUDE_PLAYABLE_DURATION = "playableDuration";

  private static final String[] PROJECTION = {
    Images.Media._ID,
    Images.Media.MIME_TYPE,
    Images.Media.BUCKET_DISPLAY_NAME,
    Images.Media.DATE_TAKEN,
    Images.Media.DATE_ADDED,
    MediaStore.MediaColumns.WIDTH,
    MediaStore.MediaColumns.HEIGHT,
    MediaStore.MediaColumns.SIZE,
    MediaStore.MediaColumns.DATA
  };

  private static final String SELECTION_BUCKET = Images.Media.BUCKET_DISPLAY_NAME + " = ?";
  private static final String SELECTION_DATE_TAKEN = Images.Media.DATE_TAKEN + " < ?";
  // NOTE: this may lead to duplicate results on subsequent queries.
  // However, should only be an issue in case a user makes multiple pictures in a second
  // AND has more than 100 pictures (current set limit in app).
  private static final String SELECTION_DATE_ADDED = Images.Media.DATE_ADDED + " <= ?";

  public CameraRollModule(ReactApplicationContext reactContext) {
    super(reactContext);
  }

  @Override
  public String getName() {
    return NAME;
  }

  /**
   * Save an image to the gallery (i.e. {@link MediaStore.Images}). This copies the original file
   * from wherever it may be to the external storage pictures directory, so that it can be scanned
   * by the MediaScanner.
   *
   * @param uri the file:// URI of the image to save
   * @param promise to be resolved or rejected
   */
  @ReactMethod
  public void saveToCameraRoll(String uri, ReadableMap options, Promise promise) {
    new SaveToCameraRoll(getReactApplicationContext(), Uri.parse(uri), options, promise)
        .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
  }

  private static class SaveToCameraRoll extends GuardedAsyncTask<Void, Void> {

    private final Context mContext;
    private final Uri mUri;
    private final Promise mPromise;
    private final ReadableMap mOptions;

    public SaveToCameraRoll(ReactContext context, Uri uri, ReadableMap options, Promise promise) {
      super(context);
      mContext = context;
      mUri = uri;
      mPromise = promise;
      mOptions = options;
    }

    @Override
    protected void doInBackgroundGuarded(Void... params) {
      File source = new File(mUri.getPath());
      FileChannel input = null, output = null;
      try {
        File environment = Environment.getExternalStoragePublicDirectory(
          Environment.DIRECTORY_DCIM);
        boolean isAlbumPresent = !"".equals(mOptions.getString("album"));

        File exportDir;
        if (isAlbumPresent) {
          exportDir = new File(environment, mOptions.getString("album"));
          if (!exportDir.exists() && !exportDir.mkdirs()) {
            mPromise.reject(ERROR_UNABLE_TO_LOAD, "Album Directory not created. Did you request WRITE_EXTERNAL_STORAGE?");
            return;
          }
        } else {
          exportDir = environment;
        }

        if (!exportDir.isDirectory()) {
          mPromise.reject(ERROR_UNABLE_TO_LOAD, "External media storage directory not available");
          return;
        }
        File dest = new File(exportDir, source.getName());
        int n = 0;
        String fullSourceName = source.getName();
        String sourceName, sourceExt;
        if (fullSourceName.indexOf('.') >= 0) {
          sourceName = fullSourceName.substring(0, fullSourceName.lastIndexOf('.'));
          sourceExt = fullSourceName.substring(fullSourceName.lastIndexOf('.'));
        } else {
          sourceName = fullSourceName;
          sourceExt = "";
        }
        while (!dest.createNewFile()) {
          dest = new File(exportDir, sourceName + "_" + (n++) + sourceExt);
        }
        input = new FileInputStream(source).getChannel();
        output = new FileOutputStream(dest).getChannel();
        output.transferFrom(input, 0, input.size());
        input.close();
        output.close();

        MediaScannerConnection.scanFile(
            mContext,
            new String[]{dest.getAbsolutePath()},
            null,
            new MediaScannerConnection.OnScanCompletedListener() {
              @Override
              public void onScanCompleted(String path, Uri uri) {
                if (uri != null) {
                  mPromise.resolve(uri.toString());
                } else {
                  mPromise.reject(ERROR_UNABLE_TO_SAVE, "Could not add image to gallery");
                }
              }
            });
      } catch (IOException e) {
        mPromise.reject(e);
      } finally {
        if (input != null && input.isOpen()) {
          try {
            input.close();
          } catch (IOException e) {
            FLog.e(ReactConstants.TAG, "Could not close input channel", e);
          }
        }
        if (output != null && output.isOpen()) {
          try {
            output.close();
          } catch (IOException e) {
            FLog.e(ReactConstants.TAG, "Could not close output channel", e);
          }
        }
      }
    }
  }

  /**
   * Get photos from {@link MediaStore.Images}, most recent first.
   *
   * @param params a map containing the following keys:
   *        <ul>
   *          <li>first (mandatory): a number representing the number of photos to fetch</li>
   *          <li>
   *            after (optional): a cursor that matches page_info[end_cursor] returned by a
   *            previous call to {@link #getPhotos}
   *          </li>
   *          <li>groupName (optional): an album name</li>
   *          <li>
   *            mimeType (optional): restrict returned images to a specific mimetype (e.g.
   *            image/jpeg)
   *          </li>
   *          <li>
   *            assetType (optional): chooses between either photos or videos from the camera roll.
   *            Valid values are "Photos" or "Videos". Defaults to photos.
   *          </li>
   *          <li>
   *            useDateAddedQuery (optional): allows for taking the 'date_added' property of images
   *            into account. In Android 10+ the default 'date_taken' property has been replaced by
   *            'date_added', resulting in possible 0 timestamps. This allows to counteract the
   *            issue.
   *          </li>
   *        </ul>
   * @param promise the Promise to be resolved when the photos are loaded; for a format of the
   *        parameters passed to this callback, see {@code getPhotosReturnChecker} in CameraRoll.js
   */
  @ReactMethod
  public void getPhotos(final ReadableMap params, final Promise promise) {
    int first = params.getInt("first");
    String after = params.hasKey("after") ? params.getString("after") : null;
    String groupName = params.hasKey("groupName") ? params.getString("groupName") : null;
    String assetType = params.hasKey("assetType") ? params.getString("assetType") : ASSET_TYPE_PHOTOS;
    long fromTime = params.hasKey("fromTime") ? (long) params.getDouble("fromTime") : 0;
    long toTime = params.hasKey("toTime") ? (long) params.getDouble("toTime") : 0;
    ReadableArray mimeTypes = params.hasKey("mimeTypes")
        ? params.getArray("mimeTypes")
        : null;
    ReadableArray include = params.hasKey("include") ? params.getArray("include") : null;

    new GetMediaTask(
          getReactApplicationContext(),
          first,
          after,
          groupName,
          mimeTypes,
          assetType,
          fromTime,
          toTime,
          include,
          promise)
          .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
  }

  private static class GetMediaTask extends GuardedAsyncTask<Void, Void> {
    private final Context mContext;
    private final int mFirst;
    private final @Nullable String mAfter;
    private final @Nullable String mGroupName;
    private final @Nullable ReadableArray mMimeTypes;
    private final String mAssetType;
    private final long mFromTime;
    private final long mToTime;
    private final Set<String> mInclude;
    private final Promise mPromise;

    private GetMediaTask(
        ReactContext context,
        int first,
        @Nullable String after,
        @Nullable String groupName,
        @Nullable ReadableArray mimeTypes,
        String assetType,
        long fromTime,
        long toTime,
        @Nullable ReadableArray include,
        Promise promise) {
      super(context);
      mContext = context;
      mFirst = first;
      mAfter = after;
      mGroupName = groupName;
      mMimeTypes = mimeTypes;
      mAssetType = assetType;
      mFromTime = fromTime;
      mToTime = toTime;
      mInclude = createSetFromIncludeArray(include);
      mPromise = promise;
    }

    private static Set<String> createSetFromIncludeArray(@Nullable ReadableArray includeArray) {
      Set<String> includeSet = new HashSet<>();

      if (includeArray == null) {
        return includeSet;
      }

      for (int i = 0; i < includeArray.size(); i++) {
        @Nullable String includeItem = includeArray.getString(i);
        if (includeItem != null) {
          includeSet.add(includeItem);
        }
      }

      return includeSet;
    }

    @Override
    protected void doInBackgroundGuarded(Void... params) {
      StringBuilder selection = new StringBuilder("1");
      List<String> selectionArgs = new ArrayList<>();
      if (!TextUtils.isEmpty(mGroupName)) {
        selection.append(" AND " + SELECTION_BUCKET);
        selectionArgs.add(mGroupName);
      }

      switch (mAssetType) {
        case ASSET_TYPE_PHOTOS:
          selection.append(" AND " + MediaStore.Files.FileColumns.MEDIA_TYPE + " = "
              + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE);
          break;
        case ASSET_TYPE_VIDEOS:
          selection.append(" AND " + MediaStore.Files.FileColumns.MEDIA_TYPE + " = "
              + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO);
          break;
        case ASSET_TYPE_ALL:
          selection.append(" AND " + MediaStore.Files.FileColumns.MEDIA_TYPE + " IN ("
              + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO + ","
              + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE + ")");
          break;
        default:
          mPromise.reject(
              ERROR_UNABLE_TO_FILTER,
              "Invalid filter option: '" + mAssetType + "'. Expected one of '" + ASSET_TYPE_PHOTOS
                  + "', '" + ASSET_TYPE_VIDEOS + "' or '" + ASSET_TYPE_ALL + "'."
          );
          return;
      }

      if (mMimeTypes != null && mMimeTypes.size() > 0) {
        selection.append(" AND " + Images.Media.MIME_TYPE + " IN (");
        for (int i = 0; i < mMimeTypes.size(); i++) {
          selection.append("?,");
          selectionArgs.add(mMimeTypes.getString(i));
        }
        selection.replace(selection.length() - 1, selection.length(), ")");
      }

      if (mFromTime > 0) {
        selection.append(" AND " + Images.Media.DATE_TAKEN + " > ?");
        selectionArgs.add(mFromTime + "");
      }
      if (mToTime > 0) {
        selection.append(" AND " + Images.Media.DATE_TAKEN + " <= ?");
        selectionArgs.add(mToTime + "");
      }

      WritableMap response = new WritableNativeMap();
      ContentResolver resolver = mContext.getContentResolver();

      try {
        // set LIMIT to first + 1 so that we know how to populate page_info
        String limit = "limit=" + (mFirst + 1);

        if (!TextUtils.isEmpty(mAfter)) {
          limit = "limit=" + mAfter + "," + (mFirst + 1);
        }

        Cursor media = resolver.query(
            MediaStore.Files.getContentUri("external").buildUpon().encodedQuery(limit).build(),
            PROJECTION,
            selection.toString(),
            selectionArgs.toArray(new String[selectionArgs.size()]),
            Images.Media.DATE_ADDED + " DESC, " + Images.Media.DATE_MODIFIED + " DESC");
        if (media == null) {
          mPromise.reject(ERROR_UNABLE_TO_LOAD, "Could not get media");
        } else {
          try {
            putEdges(resolver, media, response, mFirst, mInclude);
            if(mAfter != null) {
              Long mAfterInSeconds = Long.valueOf(mAfter) / 1000;
              putPageInfo(media, response, mFirst, mAfterInSeconds.intValue());
            } else {
              putPageInfo(media, response, mFirst, 0);
            }
          } finally {
            media.close();
            mPromise.resolve(response);
          }
        }
      } catch (SecurityException e) {
        mPromise.reject(
            ERROR_UNABLE_TO_LOAD_PERMISSION,
            "Could not get media: need READ_EXTERNAL_STORAGE permission",
            e);
      }
    }
  }

  @ReactMethod
  public void getAlbums(final ReadableMap params, final Promise promise) {
    String assetType = params.hasKey("assetType") ? params.getString("assetType") : ASSET_TYPE_ALL;
    StringBuilder selection = new StringBuilder("1");
    List<String> selectionArgs = new ArrayList<>();
    if (assetType.equals(ASSET_TYPE_PHOTOS)) {
      selection.append(" AND " + MediaStore.Files.FileColumns.MEDIA_TYPE + " = "
              + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE);
    } else if (assetType.equals(ASSET_TYPE_VIDEOS)) {
      selection.append(" AND " + MediaStore.Files.FileColumns.MEDIA_TYPE + " = "
              + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO);
    } else if (assetType.equals(ASSET_TYPE_ALL)) {
      selection.append(" AND " + MediaStore.Files.FileColumns.MEDIA_TYPE + " IN ("
              + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO + ","
              + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE + ")");
    } else {
      promise.reject(
              ERROR_UNABLE_TO_FILTER,
              "Invalid filter option: '" + assetType + "'. Expected one of '"
                      + ASSET_TYPE_PHOTOS + "', '" + ASSET_TYPE_VIDEOS + "' or '" + ASSET_TYPE_ALL + "'."
      );
      return;
    }

    final String[] projection = {MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME};

    try {
      Cursor media = getReactApplicationContext().getContentResolver().query(
              MediaStore.Files.getContentUri("external"),
              projection,
              selection.toString(),
              selectionArgs.toArray(new String[selectionArgs.size()]),
              null);
      if (media == null) {
        promise.reject(ERROR_UNABLE_TO_LOAD, "Could not get media");
      } else {
        WritableArray response = new WritableNativeArray();
        try {
          if (media.moveToFirst()) {
            Map<String, Integer> albums = new HashMap<>();
            do {
              String albumName = media.getString(media.getColumnIndex(MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME));
              if (albumName != null) {
                Integer albumCount = albums.get(albumName);
                if (albumCount == null) {
                  albums.put(albumName, 1);
                } else {
                  albums.put(albumName, albumCount + 1);
                }
              }
            } while (media.moveToNext());

            for (Map.Entry<String, Integer> albumEntry : albums.entrySet()) {
              WritableMap album = new WritableNativeMap();
              album.putString("title", albumEntry.getKey());
              album.putInt("count", albumEntry.getValue());
              response.pushMap(album);
            }
          }
        } finally {
          media.close();
          promise.resolve(response);
        }
      }
    } catch (Exception e) {
      promise.reject(ERROR_UNABLE_TO_LOAD, "Could not get media", e);
    }
  }

  private static void putPageInfo(Cursor media, WritableMap response, int limit, int offset) {
    WritableMap pageInfo = new WritableNativeMap();
    pageInfo.putBoolean("has_next_page", limit < media.getCount());
    if (limit < media.getCount()) {
      int dateTakenIndex = media.getColumnIndex(Images.Media.DATE_TAKEN);
      long dateTakenTimestamp = media.getLong(dateTakenIndex);
      pageInfo.putString(
        "end_cursor",
        dateTakenTimestamp
      );
    }
    response.putMap("page_info", pageInfo);
  }

  private static void putEdges(
      ContentResolver resolver,
      Cursor media,
      WritableMap response,
      int limit,
      Set<String> include) {
    WritableArray edges = new WritableNativeArray();
    media.moveToFirst();
    int mimeTypeIndex = media.getColumnIndex(Images.Media.MIME_TYPE);
    int groupNameIndex = media.getColumnIndex(Images.Media.BUCKET_DISPLAY_NAME);
    int dateTakenIndex = media.getColumnIndex(Images.Media.DATE_TAKEN);
    int dateAddedIndex = media.getColumnIndex(Images.Media.DATE_ADDED);
    int widthIndex = media.getColumnIndex(MediaStore.MediaColumns.WIDTH);
    int heightIndex = media.getColumnIndex(MediaStore.MediaColumns.HEIGHT);
    int sizeIndex = media.getColumnIndex(MediaStore.MediaColumns.SIZE);
    int dataIndex = media.getColumnIndex(MediaStore.MediaColumns.DATA);

    boolean includeLocation = include.contains(INCLUDE_LOCATION);
    boolean includeFilename = include.contains(INCLUDE_FILENAME);
    boolean includeFileSize = include.contains(INCLUDE_FILE_SIZE);
    boolean includeImageSize = include.contains(INCLUDE_IMAGE_SIZE);
    boolean includePlayableDuration = include.contains(INCLUDE_PLAYABLE_DURATION);

    for (int i = 0; i < limit && !media.isAfterLast(); i++) {
      WritableMap edge = new WritableNativeMap();
      WritableMap node = new WritableNativeMap();
      ExifInterface exif = getExifInterface(media, dataIndex);
      // `DATE_TAKEN` returns time in milliseconds.
      double timestamp = media.getLong(dateTakenIndex) / 1000d;

      boolean imageInfoSuccess = exif != null &&
          putImageInfo(resolver, media, node, widthIndex, heightIndex, sizeIndex, dataIndex,
              mimeTypeIndex, includeFilename, includeFileSize, includeImageSize,
              includePlayableDuration, exif);
      if (imageInfoSuccess) {
        putBasicNodeInfo(media, node, mimeTypeIndex, groupNameIndex, dateTakenIndex);
        putLocationInfo(node, dataIndex, includeLocation, exif);

        edge.putMap("node", node);
        edges.pushMap(edge);
      } else {
        // we skipped an image because we couldn't get its details (e.g. width/height), so we
        // decrement i in order to correctly reach the limit, if the cursor has enough rows
        i--;
      }
      media.moveToNext();
    }
    response.putArray("edges", edges);
  }

  private static ExifInterface getExifInterface(Cursor media, int dataIndex) {
    Uri photoUri = Uri.parse("file://" + media.getString(dataIndex));
    File file = new File(media.getString(dataIndex));
    try {
      return new ExifInterface(file.getPath());
    } catch (IOException e) {
      FLog.e(ReactConstants.TAG, "Could not get exifTimestamp for " + photoUri.toString(), e);
      return null;
    }
  }

  private static void putBasicNodeInfo(
      Cursor media,
      WritableMap node,
      int mimeTypeIndex,
      int groupNameIndex,
      double timestamp) {
    node.putString("type", media.getString(mimeTypeIndex));
    node.putString("group_name", media.getString(groupNameIndex));
    node.putDouble("timestamp", timestamp);
  }

  /**
   * @return Whether we successfully fetched all the information about the image that we were asked
   * to include
   */
  private static boolean putImageInfo(
      ContentResolver resolver,
      Cursor media,
      WritableMap node,
      int widthIndex,
      int heightIndex,
      int sizeIndex,
      int dataIndex,
      int mimeTypeIndex,
      boolean includeFilename,
      boolean includeFileSize,
      boolean includeImageSize,
      boolean includePlayableDuration,
      ExifInterface exif) {
    WritableMap image = new WritableNativeMap();
    Uri photoUri = Uri.parse("file://" + media.getString(dataIndex));
    image.putString("uri", photoUri.toString());
    String mimeType = media.getString(mimeTypeIndex);

    boolean isVideo = mimeType != null && mimeType.startsWith("video");
    boolean putImageSizeSuccess = putImageSize(resolver, node, media, image, widthIndex, heightIndex,
        photoUri, isVideo, includeImageSize, exif);
    boolean putPlayableDurationSuccess = putPlayableDuration(resolver, image, photoUri, isVideo,
        includePlayableDuration);

    if (includeFilename) {
      File file = new File(media.getString(dataIndex));
      String strFileName = file.getName();
      image.putString("filename", strFileName);
    } else {
      image.putNull("filename");
    }

    if (includeFileSize) {
      image.putDouble("fileSize", media.getLong(sizeIndex));
    } else {
      image.putNull("fileSize");
    }

    try {
      String exifTimestampString = exif.getAttribute("DateTime");
      if (exifTimestampString != null) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss");
        Date d = sdf.parse(exifTimestampString);
        node.putDouble("exif_timestamp", d.getTime());
      }
    } catch (ParseException e) {
      FLog.e(ReactConstants.TAG, "Could not parse exifTimestamp for " + photoUri.toString(), e);
      return false;
    }

    node.putMap("image", image);
    return putImageSizeSuccess && putPlayableDurationSuccess;
  }

  /**
   * @return Whether we succeeded in fetching and putting the playableDuration
   */
  private static boolean putPlayableDuration(
      ContentResolver resolver,
      WritableMap image,
      Uri photoUri,
      boolean isVideo,
      boolean includePlayableDuration) {
    image.putNull("playableDuration");

    if (!includePlayableDuration || !isVideo) {
      return true;
    }

    boolean success = true;
    @Nullable Integer playableDuration = null;
    @Nullable AssetFileDescriptor photoDescriptor = null;
    try {
      photoDescriptor = resolver.openAssetFileDescriptor(photoUri, "r");
    } catch (FileNotFoundException e) {
      success = false;
      FLog.e(ReactConstants.TAG, "Could not open asset file " + photoUri.toString(), e);
    }

    if (photoDescriptor != null) {
      MediaMetadataRetriever retriever = new MediaMetadataRetriever();
      try {
        retriever.setDataSource(photoDescriptor.getFileDescriptor());
      } catch (RuntimeException e) {
        // Do nothing. We can't handle this, and this is usually a system problem
      }
      try {
        int timeInMillisec =
            Integer.parseInt(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        playableDuration = timeInMillisec / 1000;
      } catch (NumberFormatException e) {
        success = false;
        FLog.e(
            ReactConstants.TAG,
            "Number format exception occurred while trying to fetch video metadata for "
                + photoUri.toString(),
            e);
      }
      retriever.release();
    }

    if (photoDescriptor != null) {
      try {
        photoDescriptor.close();
      } catch (IOException e) {
        // Do nothing. We can't handle this, and this is usually a system problem
      }
    }

    if (playableDuration != null) {
      image.putInt("playableDuration", playableDuration);
    }

    return success;
  }

  private static boolean putImageSize(
      ContentResolver resolver,
      WritableMap node,
      Cursor media,
      WritableMap image,
      int widthIndex,
      int heightIndex,
      Uri photoUri,
      boolean isVideo,
      boolean includeImageSize,
      ExifInterface exif) {
    image.putNull("width");
    image.putNull("height");

    if (!includeImageSize) {
      return true;
    }

    boolean success = true;
    int width = media.getInt(widthIndex);
    int height = media.getInt(heightIndex);

    if (width <= 0 || height <= 0) {
      @Nullable AssetFileDescriptor photoDescriptor = null;
      try {
        photoDescriptor = resolver.openAssetFileDescriptor(photoUri, "r");
      } catch (FileNotFoundException e) {
        success = false;
        FLog.e(ReactConstants.TAG, "Could not open asset file " + photoUri.toString(), e);
      }

      if (photoDescriptor != null) {
        if (isVideo) {
          MediaMetadataRetriever retriever = new MediaMetadataRetriever();
          try {
            retriever.setDataSource(photoDescriptor.getFileDescriptor());
          } catch (RuntimeException e) {
            // Do nothing. We can't handle this, and this is usually a system problem
          }
          try {
            width =
                Integer.parseInt(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            height =
                Integer.parseInt(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
          } catch (NumberFormatException e) {
            success = false;
            FLog.e(
                ReactConstants.TAG,
                "Number format exception occurred while trying to fetch video metadata for "
                    + photoUri.toString(),
                e);
          }
          retriever.release();
        } else {
          BitmapFactory.Options options = new BitmapFactory.Options();
          // Set inJustDecodeBounds to true so we don't actually load the Bitmap, but only get its
          // dimensions instead.
          options.inJustDecodeBounds = true;
          BitmapFactory.decodeFileDescriptor(photoDescriptor.getFileDescriptor(), null, options);
          width = options.outWidth;
          height = options.outHeight;
        }

        try {
          photoDescriptor.close();
        } catch (IOException e) {
          // Do nothing. We can't handle this, and this is usually a system problem
        }
      }

    }
    image.putInt("width", width);
    image.putInt("height", height);
    
    return success;
  }

  private static void putLocationInfo(
      WritableMap node,
      int dataIndex,
      boolean includeLocation,
      ExifInterface exif) {
    node.putNull("location");

    if (!includeLocation) {
      return;
    }

      // location details are no longer indexed for privacy reasons using string Media.LATITUDE, Media.LONGITUDE
      // we manually obtain location metadata using ExifInterface#getLatLong(float[]).
      // ExifInterface is added in API level 5
      float[] imageCoordinates = new float[2];
      boolean hasCoordinates = exif.getLatLong(imageCoordinates);
      if (hasCoordinates) {
        double longitude = imageCoordinates[1];
        double latitude = imageCoordinates[0];
        WritableMap location = new WritableNativeMap();
        location.putDouble("longitude", longitude);
        location.putDouble("latitude", latitude);
        node.putMap("location", location);
      }
  }

  /**
   * Delete a set of images.
   *
   * @param uris array of file:// URIs of the images to delete
   * @param promise to be resolved
   */
  @ReactMethod
  public void deletePhotos(ReadableArray uris, Promise promise) {
    if (uris.size() == 0) {
      promise.reject(ERROR_UNABLE_TO_DELETE, "Need at least one URI to delete");
    } else {
      new DeletePhotos(getReactApplicationContext(), uris, promise)
          .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }
  }

  private static class DeletePhotos extends GuardedAsyncTask<Void, Void> {

    private final Context mContext;
    private final ReadableArray mUris;
    private final Promise mPromise;

    public DeletePhotos(ReactContext context, ReadableArray uris, Promise promise) {
      super(context);
      mContext = context;
      mUris = uris;
      mPromise = promise;
    }

    @Override
    protected void doInBackgroundGuarded(Void... params) {
      ContentResolver resolver = mContext.getContentResolver();

      // Set up the projection (we only need the ID)
      String[] projection = { MediaStore.Images.Media._ID };

      // Match on the file path
      String innerWhere = "?";
      for (int i = 1; i < mUris.size(); i++) {
        innerWhere += ", ?";
      }

      String selection = MediaStore.Images.Media.DATA + " IN (" + innerWhere + ")";
      // Query for the ID of the media matching the file path
      Uri queryUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

      String[] selectionArgs = new String[mUris.size()];
      for (int i = 0; i < mUris.size(); i++) {
        Uri uri = Uri.parse(mUris.getString(i));
        selectionArgs[i] = uri.getPath();
      }

      Cursor cursor = resolver.query(queryUri, projection, selection, selectionArgs, null);
      int deletedCount = 0;

      while (cursor.moveToNext()) {
        long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID));
        Uri deleteUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);

        if (resolver.delete(deleteUri, null, null) == 1) {
          deletedCount++;
        }
      }

      cursor.close();

      if (deletedCount == mUris.size()) {
        mPromise.resolve(true);
      } else {
        mPromise.reject(ERROR_UNABLE_TO_DELETE,
            "Could not delete all media, only deleted " + deletedCount + " photos.");
      }
    }
  }
}
