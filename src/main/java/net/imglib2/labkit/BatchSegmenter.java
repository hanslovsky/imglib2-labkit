package net.imglib2.labkit;

import ij.ImagePlus;
import io.scif.img.ImgIOException;
import io.scif.img.ImgSaver;
import net.imagej.ops.OpEnvironment;
import net.imagej.ops.OpService;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.labkit.segmentation.Segmenter;
import net.imglib2.labkit.segmentation.weka.TrainableSegmentationSegmenter;
import net.imglib2.exception.IncompatibleTypeException;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.labkit.utils.ParallelUtils;
import net.imglib2.trainable_segmention.gson.GsonUtils;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.util.Intervals;
import org.scijava.Context;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * @author Matthias Arzt
 */
public class BatchSegmenter {

	private final Segmenter segmenter;

	public BatchSegmenter(Segmenter segmenter ) {
		this.segmenter = segmenter;
	}

	public void segment(File inputFile, File outputFile) throws Exception {
		Img<ARGBType> img = ImageJFunctions.wrap( new ImagePlus( inputFile.getAbsolutePath() ) );
		Img<UnsignedByteType> segmentation = segment(img, segmenter, Intervals.dimensionsAsIntArray(img));
		new ImgSaver().saveImg(outputFile.getAbsolutePath(), segmentation);
	}

	public static void classifyLung() throws IOException, IncompatibleTypeException, ImgIOException, InterruptedException {
		final Context context = new Context(OpService.class);
		final Img<ARGBType> rawImg = openImage();
		Segmenter segmenter = openClassifier(context);
		final int[] cellDimensions = new int[] { 256, 256 };
		Img<UnsignedByteType> segmentation = segment(rawImg, segmenter, cellDimensions);
		new ImgSaver().saveImg("/home/arzt/test.tif", segmentation);
	}

	private static Img<ARGBType> openImage() {
		final String imgPath = "/home/arzt/Documents/20170804_LungImages/2017_08_03__0006.jpg";
		return ImageJFunctions.wrap( new ImagePlus( imgPath ) );
	}

	private static Segmenter openClassifier(Context context ) throws IOException {
		final String classifierPath = "/home/arzt/Documents/20170804_LungImages/0006.classifier";
		OpEnvironment ops = context.service( OpService.class );
		return new TrainableSegmentationSegmenter( context, net.imglib2.trainable_segmention.classification.Segmenter.fromJson( ops, GsonUtils.read(classifierPath)));
	}

	public static Img<UnsignedByteType> segment(Img<ARGBType> rawImg, Segmenter segmenter, int[] cellDimensions) throws InterruptedException {
		Consumer<RandomAccessibleInterval<UnsignedByteType>> loader = target -> segmenter.segment(rawImg, target);
		Img<UnsignedByteType> result = ArrayImgs.unsignedBytes(Intervals.dimensionsAsLongArray(rawImg));
		List<Callable<Void>> chunks = ParallelUtils.chunkOperation(result, cellDimensions, loader);
		ParallelUtils.executeInParallel(
				Executors.newFixedThreadPool(10),
				ParallelUtils.addShowProgress(chunks)
		);
		return result;
	}
}
