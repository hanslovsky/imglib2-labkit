package net.imglib2.labkit;


import bdv.util.BdvHandle;
import bdv.util.BdvHandlePanel;
import bdv.util.BdvOptions;
import bdv.util.BdvSource;
import bdv.viewer.DisplayMode;
import bdv.viewer.ViewerPanel;
import net.imglib2.labkit.actions.ToggleVisibility;
import net.imglib2.labkit.bdv.BdvLayer;
import net.imglib2.labkit.control.brush.ChangeLabel;
import net.imglib2.labkit.control.brush.LabelBrushController;
import net.imglib2.labkit.labeling.LabelsLayer;
import net.imglib2.labkit.models.BitmapModel;
import net.imglib2.labkit.models.ImageLabelingModel;
import net.imglib2.labkit.panel.LabelToolsPanel;
import net.miginfocom.swing.MigLayout;
import org.scijava.ui.behaviour.util.AbstractNamedAction;

import javax.swing.*;

public class LabelingComponent implements AutoCloseable {

	private BdvHandle bdvHandle;

	private JPanel panel = new JPanel();

	private final JFrame dialogBoxOwner;

	private ActionsAndBehaviours actionsAndBehaviours;

	private ImageLabelingModel model;

	final LabelBrushController brushController;

	public JComponent getComponent() {
		return panel;
	}

	public ActionMap getActions() {
		return actionsAndBehaviours.getActions();
	}

	public LabelingComponent( final JFrame dialogBoxOwner,
			final ImageLabelingModel model )
	{
		this.model = model;
		this.dialogBoxOwner = dialogBoxOwner;

		initBdv( model.spatialDimensions().numDimensions() < 3);
		actionsAndBehaviours = new ActionsAndBehaviours(bdvHandle);
		brushController = new LabelBrushController(
				bdvHandle.getViewerPanel(),
				new BitmapModel( model ),
				actionsAndBehaviours,
				model.isTimeSeries());
		initLabelsLayer();
		initImageLayer();
		initBrushLayer();
		initPanel();
		this.model.transformationModel().initialize( bdvHandle.getViewerPanel() );
	}

	private void initBdv(boolean is2D) {
		final BdvOptions options = BdvOptions.options();
		if (is2D)
			options.is2D();
		bdvHandle = new BdvHandlePanel(dialogBoxOwner, options);
		bdvHandle.getViewerPanel().setDisplayMode( DisplayMode.FUSED );
	}

	private void initPanel() {
		panel.setLayout(new MigLayout("", "[grow]", "[][grow]"));
		panel.add(new LabelToolsPanel(bdvHandle, brushController), "wrap, growx");
		panel.add(bdvHandle.getViewerPanel(), "grow");
	}

	private void initImageLayer() {
		addBdvLayer( new BdvLayer.FinalLayer( model.showable(), "Image" ) );
	}

	private void initLabelsLayer() {
		addBdvLayer( new LabelsLayer( model ) );
	}

	public BdvSource addBdvLayer(BdvLayer layer )
	{
		BdvOptions options = BdvOptions.options().addTo( bdvHandle );
		BdvSource source = layer.image().show(layer.title(), options);
		layer.listeners().add( this::requestRepaint );
		addAction(new ToggleVisibility( layer.title(), source ));
		return source;
	}

	private void initBrushLayer()
	{
		actionsAndBehaviours.addAction( new ChangeLabel( model ) );
		bdvHandle.getViewerPanel().getDisplay().addOverlayRenderer( brushController.getBrushOverlay() );
	}

	public void addAction(AbstractNamedAction action) {
		actionsAndBehaviours.addAction(action);
	}

	private void requestRepaint() {
		bdvHandle.getViewerPanel().requestRepaint();
	}

	public ViewerPanel viewerPanel() {
		return bdvHandle.getViewerPanel();
	}

	@Override
	public void close()
	{
		bdvHandle.close();
	}
}
