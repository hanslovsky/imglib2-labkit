
package net.imglib2.labkit.utils;

import bdv.export.ProgressWriter;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.converter.Converters;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.IntegerType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Cast;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/*
 * @author Matthias Arzt
 */
public class LabkitUtils {

	public static long[] extend(long[] array, long value) {
		int length = array.length;
		long[] result = new long[length + 1];
		System.arraycopy(array, 0, result, 0, length);
		result[length] = value;
		return result;
	}

	public static int[] extend(int[] array, int value) {
		int length = array.length;
		int[] result = new int[length + 1];
		System.arraycopy(array, 0, result, 0, length);
		result[length] = value;
		return result;
	}

	public static RandomAccessibleInterval<FloatType> toFloat(
		RandomAccessibleInterval<? extends RealType<?>> rawData)
	{
		return Converters.convert(rawData, (in, out) -> out.set(in.getRealFloat()),
			new FloatType());
	}

	public static void copy(RandomAccessible<? extends IntegerType<?>> source,
		RandomAccessibleInterval<? extends IntegerType<?>> dest)
	{
		Views.interval(Views.pair(source, dest), dest).forEach(p -> p.getB()
			.setInteger(p.getA().getInteger()));
	}

	public static Img<UnsignedByteType> copyUnsignedBytes(
		RandomAccessibleInterval<? extends IntegerType<?>> source)
	{
		final long[] dimensions = Intervals.dimensionsAsLongArray(source);
		Img<UnsignedByteType> dest = ArrayImgs.unsignedBytes(dimensions);
		copy(source, dest);
		return dest;
	}

	public static <R extends NumericType<?>> Pair<Double, Double> estimateMinMax(
		RandomAccessibleInterval<R> rawData)
	{
		R firstElement = rawData.randomAccess().get();
		if (firstElement instanceof UnsignedByteType) return new ValuePair<>(0.,
			255.);
		if (firstElement instanceof ARGBType) return new ValuePair<>(0., 255.);
		if (firstElement instanceof UnsignedShortType) return tryEstimateMinMax(
			Casts.unchecked(rawData), 0., 256. * 256. - 1);
		if (firstElement instanceof RealType) return tryEstimateMinMax(Casts
			.unchecked(rawData), 0., 1.);
		return new ValuePair<>(0., 255.);
	}

	private static Pair<Double, Double> tryEstimateMinMax(
		RandomAccessibleInterval<? extends RealType<?>> rawData, double defaultMin,
		double defaultMax)
	{
		long size = Intervals.numElements(rawData);
		if (size > 1e8) return new ValuePair<>(defaultMin, defaultMax);
		return calculateMinMax(rawData);
	}

	private static Pair<Double, Double> calculateMinMax(
		RandomAccessibleInterval<? extends RealType<?>> rawData)
	{
		double min = Double.POSITIVE_INFINITY;
		double max = Double.NEGATIVE_INFINITY;
		for (RealType<?> value : Views.iterable(rawData)) {
			double d = value.getRealDouble();
			min = Math.min(min, d);
			max = Math.max(max, d);
		}
		return new ValuePair<>(min, max);
	}

	public static <T> RandomAccessibleInterval<T> populateCachedImg(
		RandomAccessibleInterval<T> img, ProgressWriter progressWriter)
	{
		if (img instanceof CachedCellImg) internPopulateCachedImg(Cast.unchecked(img), progressWriter);
		return img;
	}

	private static <T extends NativeType<T>> void internPopulateCachedImg(
		CachedCellImg<T, ?> img, ProgressWriter progressWriter)
	{
		int[] cellDimensions = new int[img.getCellGrid().numDimensions()];
		img.getCellGrid().cellDimensions(cellDimensions);
		Consumer<RandomAccessibleInterval<T>> accessPixel = target -> {
			long[] min = Intervals.minAsLongArray(target);
			RandomAccess<T> ra = target.randomAccess();
			ra.setPosition(min);
			ra.get();
		};
		List<Callable<Void>> tasks = ParallelUtils.chunkOperation(img,
			cellDimensions, accessPixel);
		final ExecutorService executor = Executors.newFixedThreadPool(Runtime
			.getRuntime().availableProcessors());
		try {
			ParallelUtils.executeInParallel(executor, ParallelUtils.addProgress(tasks,
				progressWriter));
		}
		finally {
			executor.shutdown();
		}
	}

}
