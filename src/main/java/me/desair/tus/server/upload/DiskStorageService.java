package me.desair.tus.server.upload;

import me.desair.tus.server.exception.InvalidUploadOffsetException;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.util.Utils;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Implementation of {@link UploadStorageService} that implements storage on disk
 */
public class DiskStorageService extends AbstractDiskBasedService implements UploadStorageService {

    private static final Logger log = LoggerFactory.getLogger(DiskStorageService.class);

    private static final String UPLOAD_SUB_DIRECTORY = "uploads";
    private static final String INFO_FILE = "info";
    private static final String DATA_FILE = "data";

    private Long maxUploadSize = null;
    private UploadIdFactory idFactory;

    public DiskStorageService(final UploadIdFactory idFactory, final String storagePath) {
        super(storagePath + File.separator + UPLOAD_SUB_DIRECTORY);
        Validate.notNull(idFactory, "The IdFactory cannot be null");
        this.idFactory = idFactory;
    }

    @Override
    public void setMaxUploadSize(final Long maxUploadSize) {
        this.maxUploadSize = (maxUploadSize != null && maxUploadSize > 0 ? maxUploadSize : 0);
    }

    @Override
    public long getMaxUploadSize() {
        return maxUploadSize == null ? 0 : maxUploadSize;
    }

    @Override
    public UploadInfo getUploadInfo(final String uploadUrl) throws IOException {
        return getUploadInfo(idFactory.readUploadId(uploadUrl));
    }

    @Override
    public String getUploadURI() {
        return idFactory.getUploadURI();
    }

    @Override
    public UploadInfo create(final UploadInfo info) throws IOException {
        UUID id = createNewId();

        createUploadDirectory(id);

        try {
            Path bytesPath = getBytesPath(id);

            //Create an empty file to storage the bytes of this upload
            Files.createFile(bytesPath);

            //Set starting values
            info.setId(id);
            info.setOffset(0L);

            update(info);

            return info;
        } catch (UploadNotFoundException e) {
            //Normally this cannot happen
            log.error("Unable to create UploadInfo because of an upload not found exception", e);
            return null;
        }
    }

    @Override
    public void update(final UploadInfo uploadInfo) throws IOException, UploadNotFoundException {
        Path infoPath = getInfoPath(uploadInfo.getId());
        Utils.writeSerializable(uploadInfo, infoPath);
    }

    @Override
    public UploadInfo getUploadInfo(final UUID id) throws IOException {
        try {
            Path infoPath = getInfoPath(id);
            return Utils.readSerializable(infoPath, UploadInfo.class);
        } catch (UploadNotFoundException e) {
            return null;
        }
    }

    @Override
    public UploadInfo append(final UploadInfo info, final InputStream inputStream) throws IOException, TusException {
        if(info != null) {
            Path bytesPath = getBytesPath(info.getId());

            long max = getMaxUploadSize() > 0 ? getMaxUploadSize() : Long.MAX_VALUE;
            long transferred = 0;
            Long offset = info.getOffset();
            long newOffset = offset;

            try(ReadableByteChannel uploadedBytes = Channels.newChannel(inputStream);
                FileChannel file = FileChannel.open(bytesPath, WRITE)) {

                try {
                    //Lock will be released when the channel closes
                    file.lock();

                    //Validate that the given offset is at the end of the file
                    if (!offset.equals(file.size())) {
                        throw new InvalidUploadOffsetException("The upload offset does not correspond to the written bytes. " +
                                "You can only append to the end of an upload");
                    }

                    //write all bytes in the channel up to the configured maximum
                    transferred = file.transferFrom(uploadedBytes, offset, max - offset);
                    newOffset = offset + transferred;

                } catch(Throwable ex) {
                    //An error occurred, try to write as much data as possible
                    newOffset = writeAsMuchAsPossible(file);
                    throw ex;
                }

            } finally {
                info.setOffset(newOffset);
                update(info);
            }
        }

        return info;
    }

    @Override
    public InputStream getUploadedBytes(final String uploadURI) throws IOException, UploadNotFoundException {
        InputStream inputStream = null;

        UUID id = idFactory.readUploadId(uploadURI);
        Path bytesPath = getBytesPath(id);

        //If bytesPath is not null, we know this is a valid Upload URI
        if(bytesPath != null) {
            inputStream = Channels.newInputStream(FileChannel.open(bytesPath, READ));
        }

        return inputStream;
    }

    @Override
    public void cleanupExpiredUploads(final UploadLockingService uploadLockingService) {
        //TODO
    }

    private Path getBytesPath(final UUID id) throws IOException, UploadNotFoundException {
        return getPathInUploadDir(id, DATA_FILE);
    }

    private Path getInfoPath(final UUID id) throws IOException, UploadNotFoundException {
        return getPathInUploadDir(id, INFO_FILE);
    }

    private Path createUploadDirectory(final UUID id) throws IOException {
        return Files.createDirectories(getPathInStorageDirectory(id));
    }

    private Path getPathInUploadDir(final UUID id, final String fileName) throws IOException, UploadNotFoundException {
        //Get the upload directory
        Path uploadDir = getPathInStorageDirectory(id);
        if(uploadDir != null && Files.exists(uploadDir)) {
            return uploadDir.resolve(fileName);
        } else {
            throw new UploadNotFoundException("The upload for id " + id + " was not found.");
        }
    }

    private synchronized UUID createNewId() throws IOException {
        UUID id;
        do {
            id = idFactory.createId();
            //For extra safety, double check that this ID is not in use yet
        } while(getUploadInfo(id) != null);
        return id;
    }

    private long writeAsMuchAsPossible(final FileChannel file) throws IOException {
        long offset = 0;
        if (file != null) {
            file.force(true);
            offset = file.size();
        }
        return offset;
    }
}
