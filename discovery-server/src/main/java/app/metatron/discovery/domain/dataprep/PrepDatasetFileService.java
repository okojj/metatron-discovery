/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.metatron.discovery.domain.dataprep;


import static app.metatron.discovery.domain.dataprep.PrepProperties.HADOOP_CONF_DIR;
import static app.metatron.discovery.domain.dataprep.exceptions.PrepMessageKey.MSG_DP_ALERT_CANNOT_GET_HDFS_FILE_SYSTEM;
import static app.metatron.discovery.domain.dataprep.exceptions.PrepMessageKey.MSG_DP_ALERT_CANNOT_READ_FROM_HDFS_PATH;
import static app.metatron.discovery.domain.dataprep.exceptions.PrepMessageKey.MSG_DP_ALERT_CANNOT_READ_FROM_LOCAL_PATH;
import static app.metatron.discovery.domain.dataprep.exceptions.PrepMessageKey.MSG_DP_ALERT_CANNOT_WRITE_TO_LOCAL_PATH;
import static app.metatron.discovery.domain.dataprep.exceptions.PrepMessageKey.MSG_DP_ALERT_FILE_FORMAT_WRONG;
import static app.metatron.discovery.domain.dataprep.exceptions.PrepMessageKey.MSG_DP_ALERT_HADOOP_HDFS_FAILED_TO_CONNECT;
import static app.metatron.discovery.domain.dataprep.exceptions.PrepMessageKey.MSG_DP_ALERT_MALFORMED_URI_SYNTAX;
import static app.metatron.discovery.domain.dataprep.exceptions.PrepMessageKey.MSG_DP_ALERT_REQUIRED_PROPERTY_MISSING;
import static app.metatron.discovery.domain.dataprep.exceptions.PrepMessageKey.MSG_DP_ALERT_UNKOWN_ERROR;
import static app.metatron.discovery.domain.dataprep.exceptions.PrepMessageKey.MSG_DP_ALERT_UNSUPPORTED_URI_SCHEME;
import static app.metatron.discovery.domain.dataprep.util.PrepUtil.configError;
import static app.metatron.discovery.domain.dataprep.util.PrepUtil.dataflowError;
import static app.metatron.discovery.domain.dataprep.util.PrepUtil.datasetError;

import app.metatron.discovery.domain.dataprep.entity.PrDataset;
import app.metatron.discovery.domain.dataprep.entity.PrUploadFile;
import app.metatron.discovery.domain.dataprep.exceptions.PrepErrorCodes;
import app.metatron.discovery.domain.dataprep.exceptions.PrepException;
import app.metatron.discovery.domain.dataprep.exceptions.PrepMessageKey;
import app.metatron.discovery.domain.dataprep.file.PrepCsvUtil;
import app.metatron.discovery.domain.dataprep.file.PrepJsonUtil;
import app.metatron.discovery.domain.dataprep.repository.PrDatasetRepository;
import app.metatron.discovery.domain.dataprep.teddy.DataFrame;
import app.metatron.discovery.domain.dataprep.teddy.DataFrameService;
import app.metatron.discovery.domain.dataprep.teddy.exceptions.TeddyException;
import app.metatron.discovery.domain.dataprep.transform.TeddyImpl;
import app.metatron.discovery.domain.dataprep.util.PrepUtil;
import app.metatron.discovery.domain.storage.StorageProperties;
import app.metatron.discovery.util.ExcelProcessor;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.monitorjbl.xlsx.StreamingReader;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.commons.io.FilenameUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IOUtils;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PrepDatasetFileService {

  private static Logger LOGGER = LoggerFactory.getLogger(PrepDatasetFileService.class);

  @Autowired
  PrDatasetRepository datasetRepository;

  @Autowired(required = false)
  PrepProperties prepProperties;

  @Autowired(required = false)
  StorageProperties storageProperties;

  @Autowired
  TeddyImpl teddyImpl;

  @Autowired
  DataFrameService dataFrameService;

  ExecutorService poolExecutorService = null;
  Set<Future<Map<String, Long>>> futures = null;

  public class PrepDatasetTotalLinesCallable implements Callable {

    PrDatasetRepository datasetRepository;
    PrDataset dataset;

    public PrepDatasetTotalLinesCallable(PrDatasetRepository datasetRepository, PrDataset dataset) {
      this.datasetRepository = datasetRepository;
      this.dataset = dataset;
    }

    public Map<String, Long> call() {
      Map<String, Long> result = null;
      try {
        Thread.sleep(500);

        String storedUri = dataset.getStoredUri();

        int limitRows = Integer.MAX_VALUE;
        String extensionType = FilenameUtils.getExtension(storedUri).toLowerCase();
        switch (extensionType) {
          case "xlsx":
          case "xls":
            assert false : "Excel files are treated as CSV";
            throw PrepException.create(PrepErrorCodes.PREP_DATASET_ERROR_CODE,
                    MSG_DP_ALERT_UNKOWN_ERROR, "Excel files should have converted as CSV");
          case "json":
            Configuration hadoopConf = PrepUtil.getHadoopConf(prepProperties.getHadoopConfDir(false));
            result = PrepJsonUtil.countJson(storedUri, limitRows, hadoopConf);
            break;
          default:
            hadoopConf = PrepUtil.getHadoopConf(prepProperties.getHadoopConfDir(false));
            String delimiterCol = dataset.getDelimiter();
            PrepCsvUtil csvUtil = PrepCsvUtil.DEFAULT
                    .withDelim(delimiterCol)
                    .withQuoteChar(dataset.getQuoteChar())
                    .withLimitRows(limitRows)
                    .withOnlyCount(true)
                    .withHadoopConf(hadoopConf);
            result = csvUtil.countCsvFile(storedUri);
        }

        if (result != null) {
          Long totalBytes = result.get("totalBytes");
          if (totalBytes != null) {
            dataset.setTotalBytes(totalBytes);
          }

          Long totalRows = result.get("totalRows");
          if (totalRows != null) {
            dataset.setTotalLines(totalRows);
          }
          datasetRepository.saveAndFlush(dataset);
        }
      } catch (InterruptedException e) {
        LOGGER.error("InterruptedException : {}", e.getMessage());
      } catch (Exception e) {
        e.printStackTrace();
        LOGGER.error("Failed to read file : {}", e.getMessage());
      }

      return result;
    }
  }

  public PrepDatasetFileService() {
    this.poolExecutorService = Executors.newCachedThreadPool();
    this.futures = Sets.newHashSet();
  }

  private String fileDatasetUploadLocalPath = null;
  private String fileDatasetUploadStagingPath = null;
  private String fileDatasetUploadS3Path = null;

  private void mkdirsIfNotExist(String dirUri) {
    URI uri;
    try {
      uri = new URI(dirUri);
    } catch (URISyntaxException e) {
      e.printStackTrace();
      throw PrepException
              .create(PrepErrorCodes.PREP_DATASET_ERROR_CODE, MSG_DP_ALERT_MALFORMED_URI_SYNTAX, dirUri);
    }

    switch (uri.getScheme()) {
      case "hdfs":
        Configuration conf = PrepUtil.getHadoopConf(prepProperties.getHadoopConfDir(true));
        if (conf == null) {
          throw PrepException.create(PrepErrorCodes.PREP_INVALID_CONFIG_CODE,
                  MSG_DP_ALERT_REQUIRED_PROPERTY_MISSING, HADOOP_CONF_DIR);
        }
        Path path = new Path(uri);

        FileSystem hdfsFs = null;
        try {
          hdfsFs = FileSystem.get(conf);
          if (!hdfsFs.exists(path)) {
            hdfsFs.mkdirs(path);
          }
          hdfsFs.close();
        } catch (IOException e) {
          e.printStackTrace();
          throw PrepException.create(PrepErrorCodes.PREP_DATASET_ERROR_CODE,
                  MSG_DP_ALERT_CANNOT_GET_HDFS_FILE_SYSTEM, dirUri);
        }
        break;

      case "file":
        File file = new File(uri);
        if (!file.exists()) {
          file.mkdirs();
        }
        break;

      default:
        throw PrepException
                .create(PrepErrorCodes.PREP_DATASET_ERROR_CODE, MSG_DP_ALERT_UNSUPPORTED_URI_SCHEME,
                        dirUri);
    }
  }

  private String getPathS3Base(String filename) {
    if (null == fileDatasetUploadS3Path) {
      fileDatasetUploadS3Path = prepProperties.getS3BaseDir(true) + File.separator + PrepProperties.dirUpload;
      mkdirsIfNotExist(fileDatasetUploadS3Path);
    }

    String pathStr = fileDatasetUploadS3Path + File.separator + filename;
    return pathStr;
  }

  private String getPathStagingBase(String filename) {
    if (null == fileDatasetUploadStagingPath) {
      fileDatasetUploadStagingPath = prepProperties.getStagingBaseDir(true) + File.separator + PrepProperties.dirUpload;
    }
    mkdirsIfNotExist(fileDatasetUploadStagingPath);

    String pathStr = fileDatasetUploadStagingPath + File.separator + filename;
    return pathStr;
  }

  public String getPathLocalBase(String filename) {
    if (null == fileDatasetUploadLocalPath) {
      fileDatasetUploadLocalPath = prepProperties.getLocalBaseDir() + File.separator + PrepProperties.dirUpload;
      if (fileDatasetUploadLocalPath.startsWith("file://") == false) {
        fileDatasetUploadLocalPath = "file://" + fileDatasetUploadLocalPath;
      }
      mkdirsIfNotExist(fileDatasetUploadLocalPath);
    }

    String pathStr = fileDatasetUploadLocalPath + File.separator + filename;
    return pathStr;
  }

  private List<String[]> getGridFromExcel(Sheet sheet, int limitRows, Integer columnCount) {
    List<String[]> grid = Lists.newArrayList();

    for (Row r : sheet) {
      List<String> row = Lists.newArrayList();

      int not_emtpy = 0;

      if (r == null) {
        continue;
      }
      if (limitRows <= r.getRowNum()) {
        break;
      }

      int lastColCnt = r.getLastCellNum();
      if (columnCount != null) {
        lastColCnt = columnCount;
      }

      for (int columnIndex = 0; columnIndex < lastColCnt; columnIndex++) {
        Cell c = r.getCell(columnIndex);

        if (c == null) {
          if (grid.size() > 0 && grid.get(0) != null && columnIndex < grid.get(0).length
                  && grid.get(0)[columnIndex] != null) {
            row.add("");
          } else {
            row.add(null);
          }
          continue;
        }

        String strCellValue;
        Object cellValue = ExcelProcessor.getCellValue(c);
        if (cellValue != null) {
          strCellValue = String.valueOf(cellValue);
          if (!strCellValue.isEmpty()) {
            not_emtpy++;
          }
        } else {
          strCellValue = ""; // Excel files are treated as CSV. null cannot be used
        }
        row.add(strCellValue);
      }

      if (not_emtpy > 0) {
        grid.add(row.toArray(new String[row.size()]));
        if (grid.size() >= limitRows) {
          break;
        }
      }
    }

    return grid;
  }

  private Map<String, Object> getResponseMapFromExcel(String storedUri, String extensionType, int limitRows,
          Integer columnCount, boolean autoTyping) throws IOException, TeddyException {
    Map<String, Object> responseMap = Maps.newHashMap();
    List<String> sheetNames = Lists.newArrayList();
    List<DataFrame> gridResponses = Lists.newArrayList();
    Workbook workbook;

    URI uri = null;
    try {
      uri = new URI(storedUri);
    } catch (URISyntaxException e) {
      e.printStackTrace();
      throw datasetError(MSG_DP_ALERT_MALFORMED_URI_SYNTAX, storedUri);
    }

    InputStream is = null;
    switch (uri.getScheme()) {
      case "hdfs":
        Configuration conf = PrepUtil.getHadoopConf(prepProperties.getHadoopConfDir(true));
        Path path = new Path(uri);

        FileSystem hdfsFs;
        try {
          hdfsFs = FileSystem.get(conf);
        } catch (IOException e) {
          e.printStackTrace();
          throw datasetError(MSG_DP_ALERT_CANNOT_GET_HDFS_FILE_SYSTEM, storedUri);
        }

        FSDataInputStream his;
        try {
          his = hdfsFs.open(path);
        } catch (IOException e) {
          e.printStackTrace();
          throw datasetError(MSG_DP_ALERT_CANNOT_READ_FROM_HDFS_PATH, storedUri);
        }

        is = his;
        break;

      case "file":
        File file = new File(uri);
        FileInputStream fis;
        try {
          fis = new FileInputStream(file);
        } catch (FileNotFoundException e) {
          e.printStackTrace();
          throw datasetError(MSG_DP_ALERT_CANNOT_READ_FROM_LOCAL_PATH, storedUri);
        }

        is = fis;
        break;

      default:
        throw datasetError(MSG_DP_ALERT_UNSUPPORTED_URI_SCHEME, storedUri);
    }

    if ("xls".equals(extensionType)) {       // 97~2003
      workbook = new HSSFWorkbook(is);
    } else {   // 2007 ~
      workbook = StreamingReader.builder()
              .rowCacheSize(100)
              .bufferSize(4096)
              .open(is);
    }

    if (null == workbook) {
      responseMap.put("sheetNames", sheetNames);
      responseMap.put("gridResponses", gridResponses);
      return responseMap;
    }

    for (Sheet sheet : workbook) {
      DataFrame df = new DataFrame(sheet.getSheetName());
      df.setByGrid(getGridFromExcel(sheet, limitRows, columnCount), null);

      if (autoTyping && 0 < df.rows.size()) {
        df = teddyImpl.applyAutoTyping(df);
      }

      sheetNames.add(sheet.getSheetName());
      gridResponses.add(df);
    }

    responseMap.put("sheetNames", sheetNames);
    responseMap.put("gridResponses", gridResponses);
    return responseMap;
  }

  private Map<String, Object> getResponseMapFromJson(String storedUri, int limitRows, Integer columnCount,
          boolean autoTyping) throws TeddyException {
    Map<String, Object> responseMap = Maps.newHashMap();
    List<DataFrame> gridResponses = Lists.newArrayList();
    Configuration hadoopConf = PrepUtil.getHadoopConf(prepProperties.getHadoopConfDir(false));

    DataFrame df = new DataFrame("df_for_preview");
    df.setByGrid(PrepJsonUtil.parse(storedUri, limitRows, columnCount, hadoopConf));

    if (autoTyping && 0 < df.rows.size()) {
      df = teddyImpl.applyAutoTyping(df);
    }

    gridResponses.add(df);

    responseMap.put("gridResponses", gridResponses);
    return responseMap;
  }

  private Map<String, Object> getResponseMapFromCsv(String storedUri, int limitRows,
                                                    String delimiterCol, String quoteChar,
                                                    Integer columnCount, boolean autoTyping) throws TeddyException {
    Map<String, Object> responseMap = Maps.newHashMap();
    List<DataFrame> gridResponses = Lists.newArrayList();
    Configuration hadoopConf = PrepUtil.getHadoopConf(prepProperties.getHadoopConfDir(false));

    DataFrame df = new DataFrame("df_for_preview");
    PrepCsvUtil csvUtil = PrepCsvUtil.DEFAULT
            .withDelim(delimiterCol)
            .withQuoteChar(quoteChar)
            .withLimitRows(limitRows)
            .withManualColCnt(columnCount)
            .withHadoopConf(hadoopConf);
    df.setByGrid(csvUtil.parse(storedUri));

    if (autoTyping && 0 < df.rows.size()) {
      df = teddyImpl.applyAutoTyping(df);
    }

    gridResponses.add(df);

    responseMap.put("gridResponses", gridResponses);
    return responseMap;
  }

  public void checkStoredUri(String storedUri) {

    URI uri = null;
    try {
      uri = new URI(storedUri);
    } catch (URISyntaxException e) {
      e.printStackTrace();
      throw datasetError(MSG_DP_ALERT_MALFORMED_URI_SYNTAX, storedUri);
    }

    switch (uri.getScheme()) {
      case "hdfs":
        uri.getPath();
        break;

      case "file":
        uri.getPath(); // should check for security reasons
        break;

      default:
        throw datasetError(MSG_DP_ALERT_UNSUPPORTED_URI_SCHEME, storedUri);
    }

  }

  /*
   * Response contains:
   *   success    FIXME: do not use   -> 200 or 500
   *   message    FIXME: do not use   -> error message in the exception
   *   sheets     List<String> sheet names    (Excel only)
   *   grid[]
   *     headers  FIXME: do not use   -> empty[] (CSV, Excel) or null (JSON)
   *     field[]  FIXME: only name & type is needed
   *       column name
   *       column type
   *       is dimension?
   *       column#
   *     data     List<Map<String, String>
   *     totalRows    FIXME: is this needed?
   *     sheetName    (Excel only)
   *   totalBytes     FIXME: is this needed?
   */
  public Map<String, Object> makeFileGrid(String storedUri, Integer size, String delimiterCol, String quoteChar, Integer columnCount,
          boolean autoTyping) {

    Map<String, Object> responseMap;
    String extensionType = FilenameUtils.getExtension(storedUri);
    int limitRows = size;

    try {
      switch (extensionType) {
        case "xlsx":
        case "xls":
          responseMap = getResponseMapFromExcel(storedUri, extensionType, limitRows, columnCount, autoTyping);
          break;
        case "json":
          responseMap = getResponseMapFromJson(storedUri, limitRows, columnCount, autoTyping);
          break;
        default:
          responseMap = getResponseMapFromCsv(storedUri, limitRows, delimiterCol, quoteChar, columnCount, autoTyping);
      }
    } catch (IOException e) {
      e.printStackTrace();
      throw datasetError(MSG_DP_ALERT_UNKOWN_ERROR, storedUri);
    } catch (TeddyException e) {
      e.printStackTrace();
      throw PrepException.fromTeddyException(e);
    }

    return responseMap;
  }

  public DataFrame getPreviewLinesFromFileForDataFrame(PrDataset dataset, String size)
          throws IOException, TeddyException {
    DataFrame dataFrame = null;

    if (dataset == null) {
      throw dataflowError(PrepMessageKey.MSG_DP_ALERT_NO_DATASET);
    }

    assert dataset.getImportType() == PrDataset.IMPORT_TYPE.UPLOAD
            || dataset.getImportType() == PrDataset.IMPORT_TYPE.URI;

    String storedUri = dataset.getStoredUri();

    try {
      String extensionType = FilenameUtils.getExtension(storedUri);
      int limitRows = Integer.parseInt(size);
      boolean autoTyping = true;

      if (dataset.getDsType() == PrDataset.DS_TYPE.WRANGLED) {
        autoTyping = false;
      }

      Integer columnCount = dataset.getManualColumnCount();
      Map<String, Object> responseMap = null;
      switch (extensionType) {
        case "xlsx":
        case "xls":
          // Excel files are treated as CSV
          break;
        case "json":
          responseMap = getResponseMapFromJson(storedUri, limitRows, columnCount, autoTyping);
          break;
        default:
          String delimiterCol = dataset.getDelimiter();
          String quoteChar = dataset.getQuoteChar();
          responseMap = getResponseMapFromCsv(storedUri, limitRows, delimiterCol, quoteChar, columnCount, autoTyping);
      }

      if (responseMap != null) {
        List<DataFrame> gridResponses = (List<DataFrame>) responseMap.get("gridResponses");
        if (gridResponses.isEmpty() == false) {
          dataFrame = gridResponses.get(0);
        }
      }

      dataset.setTotalLines(-1L);
      dataset.setTotalBytes(-1L);
      datasetRepository.saveAndFlush(dataset);

      Callable<Map<String, Long>> callable = new PrepDatasetTotalLinesCallable(datasetRepository, dataset);
      this.futures.add(poolExecutorService.submit(callable));
    } catch (Exception e) {
      LOGGER.error("Failed to read file : {}", e.getMessage());
      throw e;
    }

    return dataFrame;
  }

  public String getStoredUri(PrUploadFile uploadFile) {
    String storedUri = null;

    String fileName = uploadFile.getFilename();
    String extensionType = FilenameUtils.getExtension(fileName);
    if (extensionType == null || extensionType.isEmpty() == true) {
      throw datasetError(MSG_DP_ALERT_FILE_FORMAT_WRONG, "need a extension of filename");
    }

    if (uploadFile.getStorageType() == PrUploadFile.STORAGE_TYPE.LOCAL) {
      storedUri = this.getPathLocalBase(fileName);
    } else if (uploadFile.getStorageType() == PrUploadFile.STORAGE_TYPE.HDFS) {
      storedUri = this.getPathStagingBase(fileName);
    } else if (uploadFile.getStorageType() == PrUploadFile.STORAGE_TYPE.S3) {
      storedUri = this.getPathS3Base(fileName);
    } else {
      throw datasetError(MSG_DP_ALERT_UNSUPPORTED_URI_SCHEME, uploadFile.getStorageType() + " is not supported");
    }

    if (storedUri == null) {
      throw datasetError(MSG_DP_ALERT_UNSUPPORTED_URI_SCHEME, "cannot make the storedUri: " + uploadFile.toString());
    }

    return storedUri;
  }

  public Map<String, Object> uploadFileChunk(PrUploadFile uploadFile,
          Integer chunkIdx, Long chunkSize,
          MultipartFile file) {

    Map<String, Object> responseMap = Maps.newHashMap();

    FileOutputStream fos = null;
    FileChannel och = null;
    FileLock lock = null;
    try {
      URI uri = new URI(uploadFile.getLocalUri());
      File theFile = new File(uri);

      InputStream is = file.getInputStream();

      fos = new FileOutputStream(theFile, true);
      och = fos.getChannel();

      byte[] buffer = new byte[8192];

      lock = och.lock();

      long offset = chunkIdx * chunkSize;
      int byteCnt = 0;
      int totalWrote = 0;
      while (true) {
        byteCnt = is.read(buffer, 0, buffer.length);
        if (byteCnt == -1) {
          break;
        }

        if (offset < 0 || uploadFile.getFileSize() < offset + byteCnt) {
          // index error
          throw datasetError(MSG_DP_ALERT_CANNOT_WRITE_TO_LOCAL_PATH, "out of bound");
        }
        och.write(ByteBuffer.wrap(buffer, 0, byteCnt), offset);
        offset = offset + byteCnt;
        totalWrote = totalWrote + byteCnt;
      }

      responseMap.put("success", true);
      responseMap.put("localUri", uploadFile.getLocalUri());
      responseMap.put("storedUri", uploadFile.getFileUri());
      responseMap.put("chunkIdx", chunkIdx);
      responseMap.put("chunkWrote", totalWrote);
      responseMap.put("filenameBeforeUpload", uploadFile.getOriginalFilename());
      responseMap.put("createTime", uploadFile.getCreatedTime());

      uploadFile.setRestChunk(uploadFile.getRestChunk() - 1);
    } catch (Exception e) {
      LOGGER.error("Failed to upload file : {}", e.getMessage());
      responseMap.put("success", false);
      responseMap.put("message", e.getMessage());
    } finally {
      try {
        if (null != lock) {
          lock.release();
        }
        if (null != och) {
          och.close();
        }
        if (null != fos) {
          fos.close();
        }
      } catch (Exception e) {
        LOGGER.error("finally: {}", e.getMessage());
      }
    }

    return responseMap;
  }

  public void copyLocalToStaging(PrUploadFile uploadFile) {
    String storedUri = uploadFile.getFileUri();
    String localUri = uploadFile.getLocalUri();

    try {
      URI uri = new URI(localUri);
      File inFile = new File(uri);
      InputStream in = new BufferedInputStream(new FileInputStream(inFile));

      Configuration conf = PrepUtil.getHadoopConf(prepProperties.getHadoopConfDir(true));
      FileSystem fs = FileSystem.get(conf);
      Path outPath = new Path(storedUri);
      OutputStream out = fs.create(outPath);

      IOUtils.copyBytes(in, out, 8192, true);
    } catch (URISyntaxException e) {
      LOGGER.error("copyLocalToStaging: URISyntaxException", e);
      throw PrepException.create(PrepErrorCodes.PREP_DATASET_ERROR_CODE, e);
    } catch (FileNotFoundException e) {
      LOGGER.error("copyLocalToStaging: FileNotFoundException", e);
      throw PrepException.create(PrepErrorCodes.PREP_DATASET_ERROR_CODE, e);
    } catch (ConnectException e) {
      LOGGER.error("copyLocalToStaging: IOException", e);
      throw PrepException.create(PrepErrorCodes.PREP_DATASET_ERROR_CODE,
              MSG_DP_ALERT_HADOOP_HDFS_FAILED_TO_CONNECT, e.getMessage());
    } catch (IOException e) {
      LOGGER.error("copyLocalToStaging: IOException", e);
      throw PrepException.create(PrepErrorCodes.PREP_DATASET_ERROR_CODE, e);
    }
  }

  public String moveExcelToCsv(String excelStrUri, String sheetName, String delimiter) {
    String excelFilename = PrepUtil.getFileNameFromStrUri(excelStrUri);
    String csvFileName = UUID.randomUUID() + "csv";
    String csvStrUri = excelStrUri.replace(excelFilename, csvFileName);

    return moveExcelToCsv(csvStrUri, excelStrUri, sheetName, delimiter);
  }

  public String moveExcelToCsv(String csvStrUri, String excelStrUri, String sheetName, String delimiter) {
    try {
      String extensionType = FilenameUtils.getExtension(excelStrUri);

      Workbook wb;
      InputStream is = getStream(excelStrUri);
      if ("xls".equals(extensionType)) {       // 97~2003
        wb = new HSSFWorkbook(is);
      } else {   // 2007 ~
        wb = StreamingReader.builder()
                .rowCacheSize(100)
                .bufferSize(4096)
                .open(is);
      }

      Sheet sheet = wb.getSheet(sheetName);

      int maxIdx = 0;
      for (Row r : sheet) {
        for (Cell c : r) {
          int cellIdx = c.getColumnIndex();
          if (maxIdx <= cellIdx) {
            maxIdx = cellIdx + 1;
          }
        }
      }
      if (is != null) {
        is.close();
      }

      is = getStream(excelStrUri);
      if ("xls".equals(extensionType)) {       // 97~2003
        wb = new HSSFWorkbook(is);
      } else {   // 2007 ~
        wb = StreamingReader.builder()
                .rowCacheSize(100)
                .bufferSize(4096)
                .open(is);
      }

      sheet = wb.getSheet(sheetName);
      String separator = delimiter;

      Writer osw = getWriter(csvStrUri);
      for (Row r : sheet) {
        int not_empty = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxIdx; i++) {
          if (i != 0) {
            sb.append(separator);
          }

          Cell c = r.getCell(i);
          if (c == null) {
            continue;
          }

          Object cellValue = ExcelProcessor.getCellValue(c);
          if (cellValue != null) {
            String value = String.valueOf(cellValue);
            if (true == value.isEmpty()) {
              value = "\"\"";
            } else {
              if (value.contains("\"")) {
                value = value.replace("\"", "\"\"");
              }
              if (value.contains(",")) {
                value = "\"" + value + "\"";
              }
              not_empty++;
            }
            sb.append(value);
          }
        }
        sb.append("\n");
        if (0 < not_empty) {
          osw.append(sb.toString());
        }
      }
      osw.flush();
      osw.close();

    } catch (Exception e) {
      LOGGER.error("Failed to copy localFile : {}", e.getMessage());
    }

    return csvStrUri;
  }

  public InputStream getStream(String storedUri) {
    InputStream is;
    URI uri;

    try {
      uri = new URI(storedUri);
    } catch (URISyntaxException e) {
      e.printStackTrace();
      throw datasetError(MSG_DP_ALERT_MALFORMED_URI_SYNTAX, storedUri);
    }

    switch (uri.getScheme()) {
      case "hdfs":
        Configuration conf = PrepUtil.getHadoopConf(prepProperties.getHadoopConfDir(true));
        if (conf == null) {
          throw configError(MSG_DP_ALERT_REQUIRED_PROPERTY_MISSING, HADOOP_CONF_DIR);
        }
        Path path = new Path(uri);

        FileSystem hdfsFs;
        try {
          hdfsFs = FileSystem.get(conf);
        } catch (IOException e) {
          e.printStackTrace();
          throw datasetError(MSG_DP_ALERT_CANNOT_GET_HDFS_FILE_SYSTEM, storedUri);
        }

        FSDataInputStream his;
        try {
          his = hdfsFs.open(path);
        } catch (IOException e) {
          e.printStackTrace();
          throw datasetError(MSG_DP_ALERT_CANNOT_READ_FROM_HDFS_PATH, storedUri);
        }

        is = his;
        break;

      case "file":
        File file = new File(uri);

        FileInputStream fis;
        try {
          fis = new FileInputStream(file);
        } catch (FileNotFoundException e) {
          e.printStackTrace();
          throw datasetError(MSG_DP_ALERT_CANNOT_READ_FROM_LOCAL_PATH, storedUri);
        }

        is = fis;
        break;

      default:
        throw datasetError(MSG_DP_ALERT_UNSUPPORTED_URI_SCHEME, storedUri);
    }

    return is;
  }

  public Writer getWriter(String storedUri) {
    OutputStreamWriter osw = null;
    OutputStream os = null;
    URI uri;

    try {
      uri = new URI(storedUri);
    } catch (URISyntaxException e) {
      e.printStackTrace();
      throw datasetError(MSG_DP_ALERT_MALFORMED_URI_SYNTAX, storedUri);
    }

    switch (uri.getScheme()) {
      case "hdfs":
        Configuration conf = PrepUtil.getHadoopConf(prepProperties.getHadoopConfDir(true));
        if (conf == null) {
          throw configError(MSG_DP_ALERT_REQUIRED_PROPERTY_MISSING, HADOOP_CONF_DIR);
        }
        Path path = new Path(uri);

        FileSystem hdfsFs;
        try {
          hdfsFs = FileSystem.get(conf);
        } catch (IOException e) {
          e.printStackTrace();
          throw datasetError(MSG_DP_ALERT_CANNOT_GET_HDFS_FILE_SYSTEM, storedUri);
        }

        FSDataOutputStream hos;
        try {
          hos = hdfsFs.create(path);
        } catch (IOException e) {
          e.printStackTrace();
          throw datasetError(MSG_DP_ALERT_CANNOT_READ_FROM_HDFS_PATH, storedUri);
        }

        os = hos;
        break;

      case "file":
        File file = new File(uri);

        FileOutputStream fos;
        try {
          fos = new FileOutputStream(file);
        } catch (FileNotFoundException e) {
          e.printStackTrace();
          throw datasetError(MSG_DP_ALERT_CANNOT_READ_FROM_LOCAL_PATH, storedUri);
        }

        os = fos;
        break;

      default:
        throw datasetError(MSG_DP_ALERT_UNSUPPORTED_URI_SCHEME, storedUri);
    }

    osw = new OutputStreamWriter(os);

    return osw;
  }
}
