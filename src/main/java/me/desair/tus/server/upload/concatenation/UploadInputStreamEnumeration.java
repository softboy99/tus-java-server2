package me.desair.tus.server.upload.concatenation;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;

import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enumeration class that enumerates all input streams associated with with given list of uploads
 */
public class UploadInputStreamEnumeration implements Enumeration<InputStream> {

    private static final Logger log = LoggerFactory.getLogger(UploadInputStreamEnumeration.class);

    private final List<UploadInfo> uploads;
    private final UploadStorageService uploadStorageService;
    private final Iterator<UploadInfo> uploadIterator;
    private InputStream currentInputStream = null;

    public UploadInputStreamEnumeration(List<UploadInfo> uploadList, UploadStorageService uploadStorageService) {
        this.uploads = new ArrayList<>(uploadList);
        this.uploadStorageService = uploadStorageService;
        this.uploadIterator = this.uploads.iterator();
    }

    @Override
    public boolean hasMoreElements() {
        if(uploadIterator.hasNext()) {
            currentInputStream = getNextInputStream();
        }

        return currentInputStream != null;
    }

    @Override
    public InputStream nextElement() {
        return currentInputStream;
    }

    private InputStream getNextInputStream() {
        InputStream is = null;
        UploadInfo info = uploadIterator.next();
        if(info != null) {
            try {
                is = uploadStorageService.getUploadedBytes(info.getId());
            } catch (IOException | UploadNotFoundException ex) {
                log.error("Error while retrieving input stream for upload with ID " + info.getId(), ex);
                is = null;
            }
        }
        return is;
    }

}
