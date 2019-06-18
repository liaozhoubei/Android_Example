package com.example.example;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;

public class FileProvideActivity extends AppCompatActivity {

    private final int GET_PHOTO_FROM_CAMERA = 115;
    private final int GET_PHOTO_FROM_GALLERY = 114;
    private final int GET_PHOTO_FROM_CROP = 113;
    private final int REQUEST_CODE_SELECT_FILE = 112;
    private ImageView ivImg;
    private Uri cameraUri = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_provide);
        initView();
    }

    private void initView() {
        ivImg = (ImageView) findViewById(R.id.iv_img);
    }

    public void GetPic(View view) {
        PhotoUtils.openPic(this, GET_PHOTO_FROM_GALLERY);
    }

    public void TakePhoto(View view) {
        //拍照后原图回存入此路径下
        File camerafile = new File(Environment.getExternalStorageDirectory().getPath() + File.separator + System.currentTimeMillis() + ".jpg");

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M) {
            cameraUri = Uri.fromFile(camerafile);
        } else {
            /**
             * 7.0 调用系统相机拍照不再允许使用Uri方式，应该替换为FileProvider
             * 并且这样可以解决MIUI系统上拍照返回size为0的情况
             */
            cameraUri = FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".FileProvider", camerafile);
        }
        PhotoUtils.takePicture(this, cameraUri, GET_PHOTO_FROM_CAMERA);
    }

    public void GetFile(View view) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        startActivityForResult(Intent.createChooser(intent, "选择文件"), REQUEST_CODE_SELECT_FILE);
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == GET_PHOTO_FROM_CAMERA && resultCode != Activity.RESULT_CANCELED) {

            String state = Environment.getExternalStorageState();
            if (!state.equals(Environment.MEDIA_MOUNTED)) return;
            // 把原图显示到界面上
            Uri cropImageUri = Uri.fromFile(new File(getExternalCacheDir(), "face-cropped"));
            PhotoUtils.cropImageUri(this, cameraUri, cropImageUri, 1, 1, 480, 480, GET_PHOTO_FROM_CROP);

        } else if (requestCode == GET_PHOTO_FROM_GALLERY && resultCode == Activity.RESULT_OK
                && null != data) {
            try {
                getBitmapFromGallery(data);

            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(this, "获取图片失败", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == GET_PHOTO_FROM_CROP && resultCode == Activity.RESULT_OK) {
            Bitmap bitmap = getBitmepAfterCrop();
            if (bitmap == null) return;
            ivImg.setImageBitmap(bitmap);
        } else if (requestCode == REQUEST_CODE_SELECT_FILE && resultCode == Activity.RESULT_OK) {

            getDocument(data);

        }
    }

    private void getBitmapFromGallery(Intent data) {
        // 设置需要裁剪的缓存路径
        Uri cropImageUri = Uri.fromFile(new File(getExternalCacheDir(), "face-cropped"));
        // 解析真实的图片路径
        String path = PhotoUtils.getPath(this, data.getData());
        String realPath = Uri.parse(path).getPath();
        Uri newUri = FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".FileProvider", new File(realPath));
        PhotoUtils.cropImageUri(this, newUri, cropImageUri, 1, 1, 480, 480, GET_PHOTO_FROM_CROP);
    }

    private Bitmap getBitmepAfterCrop() {
        // 从缓存路径中查找图片
        Uri cropImageUri = Uri.fromFile(new File(getExternalCacheDir(), "face-cropped"));
        Bitmap bitmap = PhotoUtils.getBitmapFromUri(cropImageUri, this);
        if (bitmap == null) {
            Toast.makeText(this, "获取图片失败", Toast.LENGTH_SHORT).show();
            return null;
        }
        return bitmap;
    }

    /**
     * 从文件管理器中获取文件内容
     * 7.0 以后已经无法在获取文件的真实路径，只能通过获取其文件句柄，然后写入设置的文件夹中
     *
     * @param data
     */
    private void getDocument(Intent data) {
        Cursor returnCursor = null;
        try {
            Uri returnUri = data.getData();
            if (returnUri == null) {
                Toast.makeText(this, "获取文件失败", Toast.LENGTH_SHORT).show();
                return;
            }
            ContentResolver contentResolver = getContentResolver();
            String mimeType = contentResolver.getType(returnUri);
            returnCursor =
                    contentResolver.query(returnUri, null, null, null, null);

            if (returnCursor == null) {
                Toast.makeText(this, "获取文件失败", Toast.LENGTH_SHORT).show();
                return;
            }
            /*
             * Get the column indexes of the data in the Cursor,
             * move to the first row in the Cursor, get the data,
             * and display it.
             */
            int nameIndex = returnCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            int sizeIndex = returnCursor.getColumnIndex(OpenableColumns.SIZE);
            int document_id = returnCursor.getColumnIndex("document_id");
            returnCursor.moveToFirst();
            String fileName = returnCursor.getString(nameIndex);    // 文件名，有后缀 如  mydocument.pdf
            if (document_id > 0) {
                // 不要使用 document_id 列，因为其会对不同的文件返回不同的东西，如图片会是 image:60， 文件
                // 可能为  raw:/storage/emulated/0/Download/5.docx 或者 primary:Android/data/.nomedia 亦或 documentId:primary:Movies/1.xlsx
                // 读取没有权限的文件会直接 crash
                String documentId = returnCursor.getString(document_id);
            }


            Log.e("FileProvider", "nameIndex:" + nameIndex + "  mimeType:" + mimeType);
            // android 7.0 只能获取文件描述符进行读写
            ParcelFileDescriptor parcelFileDescriptor = getContentResolver().openFileDescriptor(returnUri, "r");
            if (parcelFileDescriptor == null) {
                FileDescriptor fd = parcelFileDescriptor.getFileDescriptor();

                boolean b = exportFileToAnother(fd, fileName);
                if (b) {

                    Toast.makeText(this, "文件已导出至：" + Environment.getExternalStorageDirectory() + File.separator + "Temp", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "访问文件失败", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "访问文件失败", Toast.LENGTH_SHORT).show();
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            Toast.makeText(this, "访问文件失败", Toast.LENGTH_SHORT).show();
        }  finally {
            returnCursor.close();
        }
    }

    /**
     * 把文件导出
     *
     * @param fd
     * @param fileName
     */
    private boolean exportFileToAnother(FileDescriptor fd, String fileName) {
        FileInputStream fis = null;
        FileOutputStream fos = null;
        boolean result = false;
        try {
            fis = new FileInputStream(fd);
            int len = 0;
            byte[] b = new byte[1024];
            File file = new File(Environment.getExternalStorageDirectory() + File.separator + "Temp");
            if (!file.exists()) {
                file.mkdirs();
            }
            File rf = new File(file.getAbsoluteFile() + File.separator + fileName);
            if (rf.exists()) {
                rf.delete();
            }
            rf.createNewFile();
            fos = new FileOutputStream(rf);
            while ((len = fis.read(b)) != -1) {
                fos.write(b, 0, len);
            }
            fos.flush();
            result = true;
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (fis != null)
                    fis.close();
                if (fos != null)
                    fos.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return result;

    }


}