/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.carbondata.core.writer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.carbondata.core.metadata.LeafNodeInfoColumnar;
import org.carbondata.core.util.CarbonUtil;
import org.carbondata.format.*;

/**
 * Writes metadata block to the fact table file in thrift format org.carbondata.format.FileMeta
 */
public class CarbonMetaDataWriter {

    // It is version number of this format class.
    private static int VERSION_NUMBER = 1;

    // Fact file path
    private String filePath;

    public CarbonMetaDataWriter(String filePath) {
        this.filePath = filePath;
    }

    /**
     * It writes FileMeta thrift format object to file.
     *
     * @param fileMeta
     * @param currentPosition At where this metadata is going to be written.
     * @throws IOException
     */
    public void writeMetaData(FileMeta fileMeta, long currentPosition) throws IOException {

        ThriftWriter thriftWriter = openThriftWriter(filePath);
        thriftWriter.write(fileMeta);
        thriftWriter.writeOffset(currentPosition);
        thriftWriter.close();
    }

    /**
     * It converts list of LeafNodeInfoColumnar to FileMeta thrift objects
     *
     * @param infoList
     * @param numCols
     * @param cardinalities
     * @return FileMeta
     */
    public FileMeta createFileMeta(List<LeafNodeInfoColumnar> infoList, int numCols,
            int[] cardinalities) {

        SegmentInfo segmentInfo = new SegmentInfo();
        segmentInfo.setNum_cols(numCols);
        segmentInfo.setColumn_cardinalities(CarbonUtil.convertToIntegerList(cardinalities));

        FileMeta fileMeta = new FileMeta();
        fileMeta.setVersion(VERSION_NUMBER);
        fileMeta.setNum_rows(getTotalNumberOfRows(infoList));
        fileMeta.setSegment_info(segmentInfo);
        fileMeta.setIndex(getLeafNodeIndex(infoList));
        //TODO: Need to set the schema here.
        fileMeta.setTable_columns(new ArrayList<ColumnSchema>());
        for (LeafNodeInfoColumnar info : infoList) {
            fileMeta.addToLeaf_node_info(getLeafNodeInfo(info));
        }
        return fileMeta;
    }

    /**
     * Get total number of rows for the file.
     *
     * @param infoList
     * @return
     */
    private long getTotalNumberOfRows(List<LeafNodeInfoColumnar> infoList) {
        long numberOfRows = 0;
        for (LeafNodeInfoColumnar info : infoList) {
            numberOfRows += info.getNumberOfKeys();
        }
        return numberOfRows;
    }

    private LeafNodeIndex getLeafNodeIndex(List<LeafNodeInfoColumnar> infoList) {

        List<LeafNodeMinMaxIndex> leafNodeMinMaxIndexes = new ArrayList<LeafNodeMinMaxIndex>();
        List<LeafNodeBTreeIndex> leafNodeBTreeIndexes = new ArrayList<LeafNodeBTreeIndex>();

        for (LeafNodeInfoColumnar info : infoList) {
            LeafNodeMinMaxIndex leafNodeMinMaxIndex = new LeafNodeMinMaxIndex();
            //TODO: Need to seperate minmax and set.
            for (byte[] minMax : info.getColumnMinMaxData()) {
                leafNodeMinMaxIndex.addToMax_values(ByteBuffer.wrap(minMax));
                leafNodeMinMaxIndex.addToMin_values(ByteBuffer.wrap(minMax));
            }
            leafNodeMinMaxIndexes.add(leafNodeMinMaxIndex);

            LeafNodeBTreeIndex leafNodeBTreeIndex = new LeafNodeBTreeIndex();
            leafNodeBTreeIndex.setStart_key(info.getStartKey());
            leafNodeBTreeIndex.setEnd_key(info.getEndKey());
            leafNodeBTreeIndexes.add(leafNodeBTreeIndex);
        }

        LeafNodeIndex leafNodeIndex = new LeafNodeIndex();
        leafNodeIndex.setMin_max_index(leafNodeMinMaxIndexes);
        leafNodeIndex.setB_tree_index(leafNodeBTreeIndexes);
        return leafNodeIndex;
    }

    private LeafNodeInfo getLeafNodeInfo(LeafNodeInfoColumnar leafNodeInfoColumnar) {

        LeafNodeInfo leafNodeInfo = new LeafNodeInfo();
        leafNodeInfo.setNum_rows(leafNodeInfoColumnar.getNumberOfKeys());

        List<DataChunk> dimDataChunks = new ArrayList<DataChunk>();
        List<DataChunk> msrDataChunks = new ArrayList<DataChunk>();
        leafNodeInfoColumnar.getKeyLengths();
        int j = 0;
        for (int i = 0; i < leafNodeInfoColumnar.getKeyLengths().length; i++) {
            DataChunk dataChunk = new DataChunk();
            dataChunk.setChunk_meta(getChunkCompressionMeta());
            boolean[] isSortedKeyColumn = leafNodeInfoColumnar.getIsSortedKeyColumn();
            //TODO : Need to find how to set it.
            dataChunk.setIs_row_chunk(false);
            //TODO : Once schema PR is merged and information needs to be passed here.
            dataChunk.setColumn_ids(new ArrayList<Integer>());
            dataChunk.setData_page_length(leafNodeInfoColumnar.getKeyLengths()[i]);
            dataChunk.setData_page_offset(leafNodeInfoColumnar.getKeyOffSets()[i]);
            dataChunk.setRle_page_offset(leafNodeInfoColumnar.getDataIndexMapOffsets()[i]);
            dataChunk.setRle_page_length(leafNodeInfoColumnar.getDataIndexMapLength()[i]);
            dataChunk.setSort_state(
                    isSortedKeyColumn[i] ? SortState.SORT_EXPLICIT : SortState.SORT_NATIVE);

            if (!isSortedKeyColumn[i]) {
                dataChunk.setRowid_page_offset(leafNodeInfoColumnar.getKeyBlockIndexOffSets()[j]);
                dataChunk.setRowid_page_length(leafNodeInfoColumnar.getKeyBlockIndexLength()[j]);
                j++;
            }

            //TODO : Right now the encodings are happening at runtime. change as per this encoders.
            //dataChunk.setEncoders(new ArrayList<Encoding>());

            dimDataChunks.add(dataChunk);
        }

        for (int i = 0; i < leafNodeInfoColumnar.getMeasureLength().length; i++) {
            DataChunk dataChunk = new DataChunk();
            dataChunk.setChunk_meta(getChunkCompressionMeta());
            dataChunk.setIs_row_chunk(false);
            //TODO : Once schema PR is merged and information needs to be passed here.
            dataChunk.setColumn_ids(new ArrayList<Integer>());
            dataChunk.setData_page_length(leafNodeInfoColumnar.getMeasureLength()[i]);
            dataChunk.setData_page_offset(leafNodeInfoColumnar.getMeasureOffset()[i]);

            //TODO : PresenceMeta needs to be implemented and set here
            // dataChunk.setPresence(new PresenceMeta());
            //TODO : Need to write ValueCompression meta here.
            //dataChunk.setEncoder_meta()
            msrDataChunks.add(dataChunk);
        }
        leafNodeInfo.setDimension_chunks(dimDataChunks);
        leafNodeInfo.setMeasure_chunks(msrDataChunks);

        return leafNodeInfo;
    }

    /**
     * Right now it is set to default values. We may use this in future
     */
    private ChunkCompressionMeta getChunkCompressionMeta() {
        ChunkCompressionMeta chunkCompressionMeta = new ChunkCompressionMeta();
        chunkCompressionMeta.setCompression_codec(CompressionCodec.SNAPPY);
        chunkCompressionMeta.setTotal_compressed_size(0);
        chunkCompressionMeta.setTotal_uncompressed_size(0);
        return chunkCompressionMeta;
    }

    /**
     * open thrift writer for writing dictionary chunk/meta object
     */
    private ThriftWriter openThriftWriter(String filePath) throws IOException {
        // create thrift writer instance
        ThriftWriter thriftWriter = new ThriftWriter(filePath, true);
        // open the file stream
        thriftWriter.open();
        return thriftWriter;
    }
}
