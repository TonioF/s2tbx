package org.esa.s2tbx.fcc.trimming;

import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import org.esa.s2tbx.fcc.common.AveragePixelsSourceBands;
import org.esa.s2tbx.fcc.common.ForestCoverChangeConstans;
import org.esa.s2tbx.fcc.common.PixelSourceBands;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.utils.AbstractImageTilesParallelComputing;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Jean Coravu
 */
public class TrimmingRegionComputingHelper extends AbstractImageTilesParallelComputing {
    private static final Logger logger = Logger.getLogger(TrimmingRegionComputingHelper.class.getName());

    private final Product segmentationSourceProduct;
    private final Product sourceProduct;
    private final int[] sourceBandIndices;

    private final Int2ObjectMap<AveragePixelsSourceBands> validRegionsMap;

    TrimmingRegionComputingHelper(Product segmentationSourceProduct, Product sourceProduct, int[] sourceBandIndices, int tileWidth, int tileHeight) {
        super(segmentationSourceProduct.getSceneRasterWidth(), segmentationSourceProduct.getSceneRasterHeight(), tileWidth, tileHeight);

        this.segmentationSourceProduct = segmentationSourceProduct;
        this.sourceProduct = sourceProduct;
        this.sourceBandIndices = sourceBandIndices;

        this.validRegionsMap = new Int2ObjectLinkedOpenHashMap<>();
    }

    @Override
    protected void runTile(int tileLeftX, int tileTopY, int tileWidth, int tileHeight, int localRowIndex, int localColumnIndex) throws IOException, IllegalAccessException {
        if (logger.isLoggable(Level.FINE)) {
            logger.log(Level.FINE, ""); // add an empty line
            logger.log(Level.FINE, "Trimming statistics for tile region: row index: "+ localRowIndex+", column index: "+localColumnIndex+", bounds [x=" + tileLeftX+", y="+tileTopY+", width="+tileWidth+", height="+tileHeight+"]");
        }

        Band firstBand = this.sourceProduct.getBandAt(this.sourceBandIndices[0]);
        Band secondBand = this.sourceProduct.getBandAt(this.sourceBandIndices[1]);
        Band thirdBand = this.sourceProduct.getBandAt(this.sourceBandIndices[2]);

        Band segmentationBand = this.segmentationSourceProduct.getBandAt(0);

        int tileBottomY = tileTopY + tileHeight;
        int tileRightX = tileLeftX + tileWidth;
        for (int y = tileTopY; y < tileBottomY; y++) {
            for (int x = tileLeftX; x < tileRightX; x++) {
                int segmentationPixelValue = segmentationBand.getSampleInt(x, y);
                if (segmentationPixelValue != ForestCoverChangeConstans.NO_DATA_VALUE) {
                    float a = firstBand.getSampleFloat(x, y);
                    float b = secondBand.getSampleFloat(x, y);
                    float c = thirdBand.getSampleFloat(x, y);

                    synchronized (this.validRegionsMap) {
                        AveragePixelsSourceBands value = this.validRegionsMap.get(segmentationPixelValue);
                        if (value == null) {
                            value = new AveragePixelsSourceBands();
                            this.validRegionsMap.put(segmentationPixelValue, value);
                        }
                        value.addPixelValuesBands(a, b, c);
                    }
                }
            }
        }
    }

    private void doClose() {
        ObjectIterator<AveragePixelsSourceBands> it = this.validRegionsMap.values().iterator();
        while (it.hasNext()) {
            AveragePixelsSourceBands value = it.next();
            WeakReference<AveragePixelsSourceBands> reference = new WeakReference<>(value);
            reference.clear();
        }
        this.validRegionsMap.clear();
    }

    IntSet computeRegionsInParallel(int threadCount, Executor threadPool) throws Exception {
        super.executeInParallel(threadCount, threadPool);

        Int2ObjectMap<PixelSourceBands> computeStatisticsPerRegion = TrimmingHelper.computeStatisticsPerRegion(this.validRegionsMap);

        doClose();

        IntSet segmentationTrimmingRegionKeys = TrimmingHelper.doTrimming(threadCount, threadPool, computeStatisticsPerRegion);

        ObjectIterator<PixelSourceBands> it = computeStatisticsPerRegion.values().iterator();
        while (it.hasNext()) {
            PixelSourceBands value = it.next();
            WeakReference<PixelSourceBands> reference = new WeakReference<PixelSourceBands>(value);
            reference.clear();
        }
        computeStatisticsPerRegion.clear();

        return segmentationTrimmingRegionKeys;
    }
}
