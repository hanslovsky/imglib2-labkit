
package net.imglib2.labkit.labeling;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import io.scif.services.DatasetIOService;
import net.imagej.DatasetService;
import net.imagej.axis.Axes;
import net.imagej.axis.CalibratedAxis;
import net.imagej.axis.DefaultLinearAxis;
import net.imagej.axis.LinearAxis;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.Point;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.labkit.utils.LabkitUtils;
import net.imglib2.img.Img;
import net.imglib2.roi.IterableRegion;
import net.imglib2.roi.labeling.ImgLabeling;
import net.imglib2.roi.labeling.LabelingMapping;
import net.imglib2.sparse.SparseIterableRegion;
import net.imglib2.type.logic.BitType;
import net.imglib2.type.numeric.IntegerType;
import net.imglib2.type.numeric.integer.IntType;
import org.apache.commons.io.FilenameUtils;
import org.scijava.Context;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * @author Matthias Arzt
 */
public class LabelingSerializer {

	private final Context context;

	public LabelingSerializer(Context context) {
		this.context = context;
	}

	public Labeling open(String filename) throws IOException {
		if (FilenameUtils.isExtension(filename, new String[] { "tif", "tiff" }))
			return openFromTiff(filename);
		if (FilenameUtils.isExtension(filename, new String[] { "labeling",
			"json" })) return openFromJson(filename);
		throw new IllegalArgumentException(
			"Filename must have supported extension (*.labeling, *.tif, *.tiff)");
	}

	private Labeling openFromJson(String filename) throws IOException {
		try (FileReader reader = new FileReader(filename)) {
			Labeling result = new Gson().fromJson(reader, Labeling.class);
			if (result == null) throw new IOException(
				"Error, labeling file is empty: " + filename);
			return result;
		}
	}

	private Labeling openFromTiff(String filename) throws IOException {
		ImgLabeling<String, ?> imgLabeling = openImgLabelingFromTiff(filename);
		return Labeling.fromImgLabeling(imgLabeling);
	}

	public ImgLabeling<String, ?> openImgLabelingFromTiff(String filename)
		throws IOException
	{
		Img<? extends IntegerType<?>> img = openImageFromTiff(filename);
		LabelsMetaData meta = (new File(filename + ".labels").exists())
			? openMetaData(filename + ".labels") : new LabelsMetaData(img);
		return fromImageAndLabelSets(img, meta.asLabelSets());
	}

	// TODO make part of imglib2-roi
	public static <L> ImgLabeling<L, ?> fromImageAndLabelSets(
		RandomAccessibleInterval<? extends IntegerType<?>> img,
		List<Set<L>> labelSets)
	{
		ImgLabeling<L, ?> result = new ImgLabeling<>(LabkitUtils.uncheckedCast(
			img));
		new LabelingMapping.SerialisationAccess<L>(result.getMapping()) {

			public void run() {
				setLabelSets(labelSets);
			}
		}.run();
		return result;
	}

	private Img<? extends IntegerType<?>> openImageFromTiff(String filename)
		throws IOException
	{
		DatasetIOService io = context.service(DatasetIOService.class);
		return LabkitUtils.uncheckedCast(io.open(filename).getImgPlus().getImg());
	}

	private LabelsMetaData openMetaData(String filename) throws IOException {
		try (FileReader reader = new FileReader(filename)) {
			return new Gson().fromJson(reader, LabelsMetaData.class);
		}
	}

	public void save(Labeling labeling, String filename) throws IOException {
		if (FilenameUtils.isExtension(filename, new String[] { "tif", "tiff" }))
			saveAsTiff(labeling, filename);
		else if (FilenameUtils.isExtension(filename, new String[] { "labeling",
			"json" })) saveAsJson(labeling, filename);
		else throw new IllegalArgumentException(
			"Filename must have supported extension (*.labeling, *.tif, *.tiff)");
	}

	private void saveAsJson(Labeling labeling, String filename)
		throws IOException
	{
		final String tmpFilename = filename + ".tmp";
		try (FileWriter writer = new FileWriter(tmpFilename)) {
			new Gson().toJson(labeling, Labeling.class, writer);
		}
		// Rename the file at the end, ensures to not corrupt an existing file,
		// it the saving is interrupted by an exception.
		Files.move(Paths.get(tmpFilename), Paths.get(filename),
			StandardCopyOption.REPLACE_EXISTING);
	}

	private <I extends IntegerType<I>> void saveAsTiff(Labeling labeling,
		String filename) throws IOException
	{
		LabelsMetaData meta = new LabelsMetaData(labeling.getLabelSets());
		try (FileWriter writer = new FileWriter(filename + ".labels")) {
			new Gson().toJson(meta, writer);
		}
		DatasetIOService io = context.service(DatasetIOService.class);
		DatasetService ds = context.service(DatasetService.class);
		RandomAccessibleInterval<I> imgPlus = LabkitUtils.uncheckedCast(labeling
			.getIndexImg());
		io.save(ds.create(imgPlus), filename);
	}

	public static class LabelsMetaData {

		List<Set<String>> labelSets;

		public LabelsMetaData(Img<? extends IntegerType<?>> img) {
			IntType max = new IntType(0);
			img.forEach(x -> {
				if (max.get() < x.getInteger()) max.set(x.getInteger());
			});
			labelSets = IntStream.rangeClosed(0, max.get()).mapToObj(i -> i == 0
				? Collections.<String> emptySet() : Collections.singleton(Integer
					.toString(i))).collect(Collectors.toList());
		}

		public LabelsMetaData(List<Set<Label>> mapping) {
			labelSets = mapping.stream().map(set -> set.stream().map(Label::name)
				.collect(Collectors.toSet())).collect(Collectors.toList());
		}

		public List<Set<String>> asLabelSets() {
			return labelSets;
		}
	}

	public static class Adapter extends TypeAdapter<Labeling> {

		private static final Type regionType =
			new TypeToken<IterableRegion<BitType>>()
			{}.getType();

		private static final Type mapType =
			new TypeToken<Map<String, IterableRegion<BitType>>>()
			{}.getType();

		@Override
		public void write(JsonWriter jsonWriter, Labeling labeling)
			throws IOException
		{
			Gson gson = new Gson();
			JsonObject jsonLabeling = new JsonObject();
			jsonLabeling.add("interval", gson.toJsonTree(new FinalInterval(labeling),
				FinalInterval.class));
			jsonLabeling.add("pixelSizes", gson.toJsonTree(getPixelSize(labeling),
				PixelSize[].class));
			jsonLabeling.add("labels", regionsToJson(labeling.iterableRegions(),
				gson));
			gson.toJson(jsonLabeling, jsonWriter);
		}

		private PixelSize[] getPixelSize(Labeling labeling) {
			return labeling.axes().stream().map(this::toPixelSize).toArray(
				PixelSize[]::new);
		}

		private PixelSize toPixelSize(CalibratedAxis calibratedAxis) {
			if (!(calibratedAxis instanceof LinearAxis)) return new PixelSize(1,
				"unknown");
			LinearAxis linear = (LinearAxis) calibratedAxis;
			return new PixelSize(linear.scale(), linear.unit());
		}

		private JsonObject regionsToJson(
			Map<Label, ? extends IterableRegion<BitType>> regions, Gson gson)
		{
			JsonObject map = new JsonObject();
			regions.forEach((label, region) -> map.add(label.name(), regionToJson(
				gson, region)));
			return map;
		}

		private JsonElement regionToJson(Gson gson,
			IterableRegion<BitType> region)
		{
			JsonArray result = new JsonArray();
			Cursor<Void> cursor = region.cursor();
			long[] coords = new long[cursor.numDimensions()];
			while (cursor.hasNext()) {
				cursor.fwd();
				cursor.localize(coords);
				result.add(gson.toJsonTree(coords, long[].class));
			}
			return result;
		}

		@Override
		public Labeling read(JsonReader jsonReader) throws IOException {
			Gson gson = new GsonBuilder().registerTypeAdapter(Labeling.class,
				new MyDeserializer()).create();
			return gson.fromJson(jsonReader, Labeling.class);
		}

		private static class MyDeserializer implements JsonDeserializer<Labeling> {

			@Override
			public Labeling deserialize(JsonElement json, Type typeOfT,
				JsonDeserializationContext context) throws JsonParseException
			{
				JsonObject object = json.getAsJsonObject();
				Interval interval = context.deserialize(object.get("interval"),
					FinalInterval.class);
				PixelSize[] axes = context.deserialize(object.get("pixelSizes"),
					PixelSize[].class);
				Map<String, IterableRegion<BitType>> regions = new TreeMap<>();
				object.getAsJsonObject("labels").entrySet().forEach(entry -> regions
					.put(entry.getKey(), regionFromJson(context, interval, entry
						.getValue())));
				Labeling labeling = regions.isEmpty() ? Labeling.createEmptyLabels(
					Collections.emptyList(), interval) : Labeling.fromMap(regions);
				labeling.setAxes(pixelSizesToAxes(axes));
				return labeling;
			}

			private List<CalibratedAxis> pixelSizesToAxes(PixelSize[] axes) {
				return Stream.of(axes).map(this::pixelSizeToAxis).collect(Collectors
					.toList());
			}

			private LinearAxis pixelSizeToAxis(PixelSize pixelSize) {
				return new DefaultLinearAxis(Axes.unknown(), pixelSize.unit,
					pixelSize.size);
			}

			private SparseIterableRegion regionFromJson(
				JsonDeserializationContext context, Interval interval,
				JsonElement jsonCoords)
			{
				SparseIterableRegion result = new SparseIterableRegion(interval);
				JsonArray array = jsonCoords.getAsJsonArray();
				Point point = new Point(interval.numDimensions());
				for (JsonElement item : array) {
					long[] coords = context.deserialize(item, long[].class);
					point.setPosition(coords);
					result.add(point);
				}
				return result;
			}
		}

		private static class PixelSize {

			public double size;
			public String unit;

			public PixelSize(double size, String unit) {
				this.size = size;
				this.unit = unit;
			}
		}
	}
}
