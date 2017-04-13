package net.imglib2.atlas;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.scijava.ui.behaviour.io.InputTriggerConfig;
import org.scijava.ui.behaviour.util.Actions;
import org.scijava.ui.behaviour.util.Behaviours;

import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.viewer.DisplayMode;
import bdv.viewer.ViewerPanel;
import gnu.trove.map.hash.TIntIntHashMap;
import hr.irb.fastRandomForest.FastRandomForest;
import ij.ImagePlus;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.Volatile;
import net.imglib2.algorithm.gauss3.Gauss3;
import net.imglib2.algorithm.gradient.PartialDerivative;
import net.imglib2.atlas.classification.UpdatePrediction;
import net.imglib2.atlas.classification.UpdatePrediction.CacheOptions;
import net.imglib2.atlas.classification.Classifier;
import net.imglib2.atlas.classification.ClassifyingCacheLoader;
import net.imglib2.atlas.classification.ClassifyingCacheLoader.ShortAccessGenerator;
import net.imglib2.atlas.classification.TrainClassifier;
import net.imglib2.atlas.classification.weka.WekaClassifier;
import net.imglib2.atlas.color.ColorMapColorProvider;
import net.imglib2.atlas.color.IntegerARGBConverters;
import net.imglib2.atlas.color.UpdateColormap;
import net.imglib2.cache.queue.BlockingFetchQueues;
import net.imglib2.cache.queue.FetcherThreads;
import net.imglib2.converter.Converter;
import net.imglib2.converter.Converters;
import net.imglib2.converter.RealFloatConverter;
import net.imglib2.exception.IncompatibleTypeException;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.ByteArray;
import net.imglib2.img.basictypeaccess.array.DirtyIntArray;
import net.imglib2.img.basictypeaccess.volatiles.array.VolatileShortArray;
import net.imglib2.img.cell.CellGrid;
import net.imglib2.img.cell.LazyCellImg;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.type.numeric.integer.ShortType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.type.volatiles.VolatileARGBType;
import net.imglib2.type.volatiles.VolatileFloatType;
import net.imglib2.util.ConstantUtils;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.view.Views;
import net.imglib2.view.composite.Composite;

public class PaintLabelsAndTrain
{

	public static void main( final String[] args ) throws IncompatibleTypeException, IOException
	{

		final Random rng = new Random();
		final String imgPath = System.getProperty( "user.home" ) + "/Downloads/epfl-em/training.tif";
		final Img< UnsignedByteType > rawImg = ImageJFunctions.wrapByte( new ImagePlus( imgPath ) );
		final long[] dimensions = Intervals.dimensionsAsLongArray( rawImg );

		final int[] cellDimensions = new int[] { 128, 128, 2 };
		final CellGrid grid = new CellGrid( dimensions, cellDimensions );
		final int maxNumLevels = 1;
		final int numFetcherThreads = Runtime.getRuntime().availableProcessors();
		final BlockingFetchQueues< Callable< ? > > queue = new BlockingFetchQueues<>( maxNumLevels );
		new FetcherThreads( queue, numFetcherThreads );

		final int nLabels = 2;
		final List< String > classLabels = IntStream.range( 0, nLabels ).mapToObj( l -> "class " + l ).collect( Collectors.toList() );

		final ArrayImg< UnsignedByteType, ByteArray > rawData = ArrayImgs.unsignedBytes( dimensions );
		for ( final Pair< UnsignedByteType, UnsignedByteType > p : Views.interval( Views.pair( rawImg, rawData ), rawImg ) )
			p.getB().set( p.getA() );
		final ArrayList< RandomAccessibleInterval< FloatType > > featuresList = new ArrayList<>();
		final ArrayList< RandomAccessibleInterval< VolatileFloatType > > vfeaturesList = new ArrayList<>();
		final RandomAccessibleInterval< FloatType > converted = Converters.convert( ( RandomAccessibleInterval< UnsignedByteType > ) rawData, new RealFloatConverter<>(), new FloatType() );
		featuresList.add( Views.addDimension( converted, 0, 0 ) );
		vfeaturesList.add( Converters.convert( featuresList.get( 0 ), ( Converter< FloatType, VolatileFloatType > ) ( input, output ) -> {
			output.setValid( true );
			output.set( input.get() );
		}, new VolatileFloatType() ) );
		final double[] sigmas = { 1.0 }; // , 3.0, 5.0, 7.0 };
		@SuppressWarnings( "unchecked" )
		final Pair< Img< FloatType >, Img< VolatileFloatType > >[] gausses = new Pair[ sigmas.length ];
		for ( int sigmaIndex = 0; sigmaIndex < sigmas.length; ++sigmaIndex )
		{
			final double sigma = sigmas[ sigmaIndex ];
			final double sigmaDiff = sigmaIndex == 0 ? sigma : Math.sqrt( sigma * sigma - sigmas[ sigmaIndex - 1 ] * sigmas[ sigmaIndex - 1 ] );
//			final ArrayImg< FloatType, FloatArray > gauss = ArrayImgs.floats( Intervals.dimensionsAsLongArray( converted ) );
//			Gauss3.gauss( sigma, Views.extendBorder( converted ), gauss );
			final RandomAccessibleInterval< FloatType > gaussSource = sigmaIndex == 0 ? converted : gausses[ sigmaIndex - 1 ].getA();
			final FeatureGeneratorLoader< FloatType > gaussLoader = new FeatureGeneratorLoader<>( grid, target -> {
				Gauss3.gauss( sigmaDiff, Views.extendBorder( gaussSource ), target );
			} );
			final Pair< Img< FloatType >, Img< VolatileFloatType > > gauss = FeatureGeneratorLoader.createFeatures( gaussLoader, "gauss-" + sigma + "-", 1000, queue );
			gausses[ sigmaIndex ] = gauss;
			featuresList.add( Views.addDimension( gauss.getA(), 0, 0 ) );
			vfeaturesList.add( Views.addDimension( gauss.getB(), 0, 0 ) );

			@SuppressWarnings( "unchecked" )
			final Pair< Img< FloatType >, Img< VolatileFloatType > >[] gradients = new Pair[ converted.numDimensions() ];
			for ( int d = 0; d < converted.numDimensions(); ++d )
			{
				final int finalD = d;
				final FeatureGeneratorLoader< FloatType > gradientLoader = new FeatureGeneratorLoader<>( grid, target -> {
					PartialDerivative.gradientCentralDifference2( Views.extendBorder( gauss.getA() ), target, finalD );
				} );
				final Pair< Img< FloatType >, Img< VolatileFloatType > > grad = FeatureGeneratorLoader.createFeatures( gradientLoader, "gradient-" + d + "-" + sigma + "-", 1000, queue );
				gradients[ d ] = grad;
			}

			final FeatureGeneratorLoader< FloatType > gradientMagnitudeLoader = new FeatureGeneratorLoader<>( grid, target -> {
				final FloatType ft = new FloatType();
				for ( int d = 0; d < gradients.length; ++d )
					for ( final Pair< FloatType, FloatType > p : Views.interval( Views.pair( gradients[ d ].getA(), target ), target ) )
					{
						final float v = p.getA().get();
						ft.set( v * v );
						p.getB().add( ft );
					}
			} );

			final Pair< Img< FloatType >, Img< VolatileFloatType > > gradientMagnitude = FeatureGeneratorLoader.createFeatures( gradientMagnitudeLoader, "gradient-magnitude-" + sigma + "-", 1000, queue );

			featuresList.add( Views.addDimension( gradientMagnitude.getA(), 0, 0 ) );
			vfeaturesList.add( Views.addDimension( gradientMagnitude.getB(), 0, 0 ) );
		}

		final RandomAccessibleInterval< FloatType > features = Views.concatenate( 3, featuresList );
		final RandomAccessibleInterval< VolatileFloatType > vfeatures = Views.concatenate( 3, vfeaturesList );

		final FastRandomForest wekaClassifier = new FastRandomForest();
		final WekaClassifier< FloatType, ShortType > classifier = new WekaClassifier<>( wekaClassifier, classLabels, ( int ) features.dimension( features.numDimensions() - 1 ) );

		final ShortAccessGenerator< VolatileShortArray > accessGenerator = ( n, valid ) -> new VolatileShortArray( ( int ) n, valid );
		trainClassifier( rawData, features, vfeatures, classifier, nLabels, grid, queue, accessGenerator, rng );
	}

	// change the accessGenerator once I can use something different than
	// VolatileFloatArray
	public static < R extends RealType< R >, F extends RealType< F >, VF extends Volatile< F > >
	void trainClassifier(
			final RandomAccessibleInterval< R > rawData,
			final RandomAccessibleInterval< F > features,
			final RandomAccessibleInterval< VF > volatileFeatures,
			final Classifier< Composite< F >, RandomAccessibleInterval< F >, RandomAccessibleInterval< ShortType > > classifier,
			final int nLabels,
			final CellGrid grid,
			final BlockingFetchQueues< Callable< ? > > queue,
			final ShortAccessGenerator< VolatileShortArray > accessGenerator,
			final Random rng ) throws IOException
	{

		final int nFeatures = ( int ) features.dimension( features.numDimensions() - 1 );
		final TIntIntHashMap cmap = new TIntIntHashMap();
		cmap.put( LabelBrushController.BACKGROUND, 0 );
		final ColorMapColorProvider colorProvider = new ColorMapColorProvider( cmap );

		final InputTriggerConfig config = new InputTriggerConfig();
		final Behaviours behaviors = new Behaviours( config );

//		final BdvStackSource< ? extends RealType< ? > > bdv = BdvFunctions.show( rawData, "raw" );

		final BdvStackSource< VF > bdv = BdvFunctions.show( Views.hyperSlice( volatileFeatures, volatileFeatures.numDimensions() - 1, 0 ), "feature 1" );
		bdv.getBdvHandle().getSetupAssignments().getMinMaxGroups().get( 0 ).setRange( 0, 255 );
		bdv.getBdvHandle().getViewerPanel().setDisplayMode( DisplayMode.GROUP );
		bdv.getBdvHandle().getViewerPanel().getVisibilityAndGrouping().addSourceToGroup( 0, 0 );
		for ( int feat = 1; feat < nFeatures; ++feat )
		{
			BdvFunctions.show( Views.hyperSlice( volatileFeatures, volatileFeatures.numDimensions() - 1, feat ), "feature " + ( feat + 1 ), BdvOptions.options().addTo( bdv ) );
			bdv.getBdvHandle().getSetupAssignments().getMinMaxGroups().get( feat ).setRange( 0, 255 );
			bdv.getBdvHandle().getViewerPanel().getVisibilityAndGrouping().addSourceToGroup( feat, feat );
		}

//		final BdvStackSource< VF > bdv = BdvFunctions.show( selectingVFeatures, "features" );
		final ViewerPanel viewer = bdv.getBdvHandle().getViewerPanel();

		final MouseWheelChannelSelector mouseWheelSelector = new MouseWheelChannelSelector( viewer, nFeatures );
		behaviors.behaviour( mouseWheelSelector, "mouseweheel selector", "shift F scroll" );
		behaviors.behaviour( mouseWheelSelector.getOverlay(), "feature selector overlay", "shift F" );
		viewer.getDisplay().addOverlayRenderer( mouseWheelSelector.getOverlay() );

		final LazyCellImg< IntType, DirtyIntArray > labels = LabelLoader.createImg( new LabelLoader( grid, LabelBrushController.BACKGROUND ), "labels-", 1000 );
//		final ArrayImg< IntType, IntArray > labels = ArrayImgs.ints( Intervals.dimensionsAsLongArray( rawData ) );
//		for ( final IntType l : labels )
//			l.set( LabelBrushController.BACKGROUND );

		final LabelBrushController brushController = new LabelBrushController(
				viewer,
				labels,
				new AffineTransform3D(),
				behaviors,
				nLabels,
				LabelBrushController.emptyGroundTruth(),
				colorProvider );
		final UpdateColormap colormapUpdater = new UpdateColormap( colorProvider, nLabels, rng, viewer, 1.0f );
		colormapUpdater.updateColormap();
//		final SparseIntRandomAccessibleInterval< UnsignedShortType > labels = new SparseIntRandomAccessibleInterval<>( brushController.getGroundTruth(), rawData, new UnsignedShortType(), LabelBrushController.BACKGROUND );
		BdvFunctions.show( Converters.convert( ( RandomAccessibleInterval< IntType > ) labels, new IntegerARGBConverters.ARGB<>( colorProvider ), new ARGBType() ), "labels", BdvOptions.options().addTo( bdv ) );

		final RealRandomAccessible< VolatileARGBType > emptyPrediction = ConstantUtils.constantRealRandomAccessible( new VolatileARGBType( 0 ), labels.numDimensions() );
		final RealRandomAccessibleContainer< VolatileARGBType > container = new RealRandomAccessibleContainer<>( emptyPrediction );
		BdvFunctions.show( container, labels, "prediction", BdvOptions.options().addTo( bdv ) );
		for ( int n = 0; n < nFeatures; ++n )
		{
			bdv.getBdvHandle().getViewerPanel().getVisibilityAndGrouping().addSourceToGroup( nFeatures, n );
			bdv.getBdvHandle().getViewerPanel().getVisibilityAndGrouping().addSourceToGroup( nFeatures + 1, n );
		}
		behaviors.install( bdv.getBdvHandle().getTriggerbindings(), "paint ground truth" );
		bdv.getBdvHandle().getViewerPanel().getDisplay().addOverlayRenderer( brushController.getBrushOverlay() );
//		final BdvStackSource< VF > featuresBdv = BdvFunctions.show( volatileFeatures, "features" );
//		featuresBdv.getBdvHandle().getSetupAssignments().getMinMaxGroups().get( 0 ).setRange( 0, 255 );

		final Actions actions = new Actions( config );
		actions.install( bdv.getBdvHandle().getKeybindings(), "paint ground truth" );

		final ArrayList< String > classes = new ArrayList<>();
		for ( int i = 1; i <= nLabels; ++i )
			classes.add( "" + i );

		final TrainClassifier< F > trainer = new TrainClassifier<>( classifier, brushController, features, classes );
		actions.namedAction( trainer, "ctrl shift T" );
		actions.namedAction( colormapUpdater, "ctrl shift C" );

		final CacheOptions cacheOptions = new UpdatePrediction.CacheOptions( "prediction", grid, 1000, queue );
		final ClassifyingCacheLoader< F, VolatileShortArray > classifyingLoader = new ClassifyingCacheLoader<>( grid, features, classifier, nFeatures, accessGenerator );


		final UpdatePrediction< F > predictionAdder = new UpdatePrediction<>( viewer, classifyingLoader, colorProvider, cacheOptions, container );
		trainer.addListener( predictionAdder );

	}


}