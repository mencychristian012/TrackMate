/*-
 * #%L
 * TrackMate: your buddy for everyday tracking.
 * %%
 * Copyright (C) 2010 - 2024 TrackMate developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
package fiji.plugin.trackmate.visualization.table;

import static fiji.plugin.trackmate.gui.Icons.CSV_ICON;
import static fiji.plugin.trackmate.gui.Icons.TRACKMATE_ICON;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.event.WindowAdapter;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.filechooser.FileNameExtensionFilter;

import org.jgrapht.graph.DefaultWeightedEdge;

import fiji.plugin.trackmate.Dimension;
import fiji.plugin.trackmate.Model;
import fiji.plugin.trackmate.ModelChangeEvent;
import fiji.plugin.trackmate.ModelChangeListener;
import fiji.plugin.trackmate.SelectionChangeEvent;
import fiji.plugin.trackmate.SelectionChangeListener;
import fiji.plugin.trackmate.SelectionModel;
import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.features.FeatureUtils;
import fiji.plugin.trackmate.features.manual.ManualEdgeColorAnalyzer;
import fiji.plugin.trackmate.features.manual.ManualSpotColorAnalyzerFactory;
import fiji.plugin.trackmate.gui.displaysettings.DisplaySettings;
import fiji.plugin.trackmate.gui.displaysettings.DisplaySettings.UpdateListener;
import fiji.plugin.trackmate.util.FileChooser;
import fiji.plugin.trackmate.util.FileChooser.DialogType;
import fiji.plugin.trackmate.util.FileChooser.SelectionMode;
import fiji.plugin.trackmate.util.TMUtils;
import fiji.plugin.trackmate.visualization.FeatureColorGenerator;
import fiji.plugin.trackmate.visualization.TrackMateModelView;
import fiji.plugin.trackmate.visualization.trackscheme.utils.SearchBar;

public class TrackTableView extends JFrame implements TrackMateModelView, ModelChangeListener, SelectionChangeListener
{

	private static final long serialVersionUID = 1L;

	private static final String KEY = "TRACK_TABLES";

	private final Model model;

	private final TablePanel< Spot > spotTable;

	private final TablePanel< DefaultWeightedEdge > edgeTable;

	private final TablePanel< Integer > trackTable;

	private final AtomicBoolean ignoreSelectionChange = new AtomicBoolean( false );

	private final SelectionModel selectionModel;

	private String imagePath;

	public TrackTableView( final Model model, final SelectionModel selectionModel, final DisplaySettings ds, final String imagePath )
	{
		super( "Track tables" );
		this.imagePath = imagePath;
		setIconImage( TRACKMATE_ICON.getImage() );
		this.model = model;
		this.selectionModel = selectionModel;

		/*
		 * GUI.
		 */

		final JPanel mainPanel = new JPanel();
		mainPanel.setLayout( new BorderLayout() );

		// Tables.
		this.spotTable = createSpotTable( model, ds );
		this.edgeTable = createEdgeTable( model, ds );
		this.trackTable = createTrackTable( model, ds );

		// Listeners.
		spotTable.getTable().getSelectionModel().addListSelectionListener(
				new SpotTableSelectionListener() );
		edgeTable.getTable().getSelectionModel().addListSelectionListener(
				new EdgeTableSelectionListener() );
		trackTable.getTable().getSelectionModel().addListSelectionListener(
				new TrackTableSelectionListener() );

		// Tabbed pane.
		final JTabbedPane tabbedPane = new JTabbedPane( JTabbedPane.LEFT );
		tabbedPane.add( "Spots", spotTable.getPanel() );
		tabbedPane.add( "Edges", edgeTable.getPanel() );
		tabbedPane.add( "Tracks", trackTable.getPanel() );

		tabbedPane.setSelectedComponent( spotTable.getPanel() );
		mainPanel.add( tabbedPane, BorderLayout.CENTER );

		// Tool bar.
		final JPanel toolbar = new JPanel();
		final BoxLayout layout = new BoxLayout( toolbar, BoxLayout.LINE_AXIS );
		toolbar.setLayout( layout );
		final JButton exportBtn = new JButton( "Export to CSV", CSV_ICON );
		exportBtn.addActionListener( e -> exportToCsv( tabbedPane.getSelectedIndex() ) );
		toolbar.add( exportBtn );
		toolbar.add( Box.createHorizontalGlue() );
		final SearchBar searchBar = new SearchBar( model, this );
		searchBar.setMaximumSize( new java.awt.Dimension( 160, 30 ) );
		toolbar.add( searchBar );
		final JToggleButton tglColoring = new JToggleButton( "coloring" );
		tglColoring.addActionListener( e -> {
			spotTable.setUseColoring( tglColoring.isSelected() );
			edgeTable.setUseColoring( tglColoring.isSelected() );
			trackTable.setUseColoring( tglColoring.isSelected() );
			refresh();
		} );
		toolbar.add( tglColoring );
		mainPanel.add( toolbar, BorderLayout.NORTH );

		getContentPane().add( mainPanel );
		pack();

		/*
		 * Listeners.
		 */

		final UpdateListener refresher = () -> refresh();
		ds.listeners().add( refresher );
		selectionModel.addSelectionChangeListener( this );
		model.addModelChangeListener( this );
		addWindowListener( new WindowAdapter()
		{
			@Override
			public void windowClosing( final java.awt.event.WindowEvent e )
			{
				selectionModel.removeSelectionChangeListener( TrackTableView.this );
				model.removeModelChangeListener( TrackTableView.this );
				ds.listeners().remove( refresher );
			};
		} );
	}

//	private void exportToCsv( final int index )
//	{
//		final int lastIndexOf = imagePath.lastIndexOf( '_' );
//		if ( lastIndexOf > 0 )
//			imagePath = imagePath.substring( 0, lastIndexOf );
//
//		final TablePanel< ? > table;
//		String selectedFile;
//		switch ( index )
//		{
//		case 0:
//			selectedFile = imagePath + "_spots.csv";
//			table = spotTable;
//			break;
//		case 1:
//			selectedFile = imagePath + "_edges.csv";
//			table = edgeTable;
//			break;
//		case 2:
//			selectedFile = imagePath + "_tracks.csv";
//			table = trackTable;
//			break;
//		default:
//			throw new IllegalArgumentException( "Unknown table with index " + index );
//		}
//
//		final File file = FileChooser.chooseFile(
//				this,
//				selectedFile,
//				new FileNameExtensionFilter( "CSV files", "csv" ),
//				"Export table to CSV",
//				DialogType.SAVE,
//				SelectionMode.FILES_ONLY );
//		if ( null == file )
//			return;
//
//		selectedFile = file.getAbsolutePath();
//		try
//		{
//			table.exportToCsv( file );
//		}
//		catch ( final IOException e )
//		{
//			model.getLogger().error( "Problem exporting to file "
//					+ file + "\n" + e.getMessage() );
//		}
//		imagePath = selectedFile;
//	}

	private void exportToCsv(final int tabIndex) {
		// Strip any existing suffix from the image path
		final int lastSuffixIndex = imagePath.lastIndexOf('_');
		final String baseFilePath = (lastSuffixIndex > 0)
				? imagePath.substring(0, lastSuffixIndex)
				: imagePath;

		// Use clear variable names to explain what each tab index represents
		final boolean isSpotTab = tabIndex == 0;
		final boolean isEdgeTab = tabIndex == 1;
		final boolean isTrackTab = tabIndex == 2;

		// Select the appropriate table and filename based on tab type
		final TablePanel<?> tableToExport;
		String exportFilename;

		if (isSpotTab) {
			exportFilename = baseFilePath + "_spots.csv";
			tableToExport = spotTable;
		} else if (isEdgeTab) {
			exportFilename = baseFilePath + "_edges.csv";
			tableToExport = edgeTable;
		} else if (isTrackTab) {
			exportFilename = baseFilePath + "_tracks.csv";
			tableToExport = trackTable;
		} else {
			throw new IllegalArgumentException("Unknown table with index " + tabIndex);
		}

		// Show file chooser dialog
		final File file = FileChooser.chooseFile(
				this,
				exportFilename,
				new FileNameExtensionFilter("CSV files", "csv"),
				"Export table to CSV",
				DialogType.SAVE,
				SelectionMode.FILES_ONLY);

		if (file == null)
			return;

		// Export the data
		final String selectedFilePath = file.getAbsolutePath();
		try {
			tableToExport.exportToCsv(file);
		}
		catch (final IOException e) {
			model.getLogger().error("Problem exporting to file "
					+ file + "\n" + e.getMessage());
		}

		// Update the image path for next export
		imagePath = selectedFilePath;
	}

	public static final TablePanel< Integer > createTrackTable( final Model model, final DisplaySettings ds )
	{
		final List< Integer > objects = new ArrayList<>( model.getTrackModel().trackIDs( true ) );
		final List< String > features = new ArrayList<>( model.getFeatureModel().getTrackFeatures() );
		final BiFunction< Integer, String, Double > featureFun = ( trackID, feature ) -> model.getFeatureModel().getTrackFeature( trackID, feature );
		final Map< String, String > featureNames = model.getFeatureModel().getTrackFeatureNames();
		final Map< String, String > featureShortNames = model.getFeatureModel().getTrackFeatureShortNames();
		final Map< String, String > featureUnits = new HashMap<>();
		for ( final String feature : features )
		{
			final Dimension dimension = model.getFeatureModel().getTrackFeatureDimensions().get( feature );
			final String units = TMUtils.getUnitsFor( dimension, model.getSpaceUnits(), model.getTimeUnits() );
			featureUnits.put( feature, units );
		}
		final Map< String, Boolean > isInts = model.getFeatureModel().getTrackFeatureIsInt();
		final Map< String, String > infoTexts = new HashMap<>();
		final Function< Integer, String > labelGenerator = id -> model.getTrackModel().name( id );
		final BiConsumer< Integer, String > labelSetter = ( id, label ) -> model.getTrackModel().setName( id, label );

		final Supplier< FeatureColorGenerator< Integer > > coloring =
				() -> FeatureUtils.createWholeTrackColorGenerator( model, ds );

		final TablePanel< Integer > table =
				new TablePanel<>(
						objects,
						features,
						featureFun,
						featureNames,
						featureShortNames,
						featureUnits,
						isInts,
						infoTexts,
						coloring,
						labelGenerator,
						labelSetter );
		return table;
	}

	public static final TablePanel< DefaultWeightedEdge > createEdgeTable( final Model model, final DisplaySettings ds )
	{
		final List< DefaultWeightedEdge > objects = new ArrayList<>();
		for ( final Integer trackID : model.getTrackModel().unsortedTrackIDs( true ) )
			objects.addAll( model.getTrackModel().trackEdges( trackID ) );
		final List< String > features = new ArrayList<>( model.getFeatureModel().getEdgeFeatures() );
		final Map< String, String > featureNames = model.getFeatureModel().getEdgeFeatureNames();
		final Map< String, String > featureShortNames = model.getFeatureModel().getEdgeFeatureShortNames();
		final Map< String, String > featureUnits = new HashMap<>();
		for ( final String feature : features )
		{
			final Dimension dimension = model.getFeatureModel().getEdgeFeatureDimensions().get( feature );
			final String units = TMUtils.getUnitsFor( dimension, model.getSpaceUnits(), model.getTimeUnits() );
			featureUnits.put( feature, units );
		}
		final Map< String, Boolean > isInts = model.getFeatureModel().getEdgeFeatureIsInt();
		final Map< String, String > infoTexts = new HashMap<>();
		final Function< DefaultWeightedEdge, String > labelGenerator = edge -> String.format( "%s → %s",
				model.getTrackModel().getEdgeSource( edge ).getName(), model.getTrackModel().getEdgeTarget( edge ).getName() );
		final BiConsumer< DefaultWeightedEdge, String > labelSetter = null;

		/*
		 * Feature provider. We add a fake one to show the spot track ID.
		 */
		final String TRACK_ID = "TRACK_ID";
		features.add( 0, TRACK_ID );
		featureNames.put( TRACK_ID, "Track ID" );
		featureShortNames.put( TRACK_ID, "Track ID" );
		featureUnits.put( TRACK_ID, "" );
		isInts.put( TRACK_ID, Boolean.TRUE );
		infoTexts.put( TRACK_ID, "The id of the track this spot belongs to." );

		final BiFunction< DefaultWeightedEdge, String, Double > featureFun = ( edge, feature ) -> {
			if ( feature.equals( TRACK_ID ) )
			{
				final Integer trackID = model.getTrackModel().trackIDOf( edge );
				return trackID == null ? null : trackID.doubleValue();
			}
			return model.getFeatureModel().getEdgeFeature( edge, feature );
		};

		final BiConsumer< DefaultWeightedEdge, Color > colorSetter =
				( edge, color ) -> model.getFeatureModel().putEdgeFeature( edge, ManualEdgeColorAnalyzer.FEATURE, Double.valueOf( color.getRGB() ) );

		final Supplier< FeatureColorGenerator< DefaultWeightedEdge > > coloring =
				() -> FeatureUtils.createTrackColorGenerator( model, ds );

		final TablePanel< DefaultWeightedEdge > table =
				new TablePanel<>(
						objects,
						features,
						featureFun,
						featureNames,
						featureShortNames,
						featureUnits,
						isInts,
						infoTexts,
						coloring,
						labelGenerator,
						labelSetter,
						ManualEdgeColorAnalyzer.FEATURE,
						colorSetter );
		return table;
	}

	public static final TablePanel< Spot > createSpotTable( final Model model, final DisplaySettings ds )
	{
		final List< Spot > objects = new ArrayList<>();
		for ( final Integer trackID : model.getTrackModel().unsortedTrackIDs( true ) )
			objects.addAll( model.getTrackModel().trackSpots( trackID ) );
		final List< String > features = new ArrayList<>( model.getFeatureModel().getSpotFeatures() );
		final Map< String, String > featureNames = model.getFeatureModel().getSpotFeatureNames();
		final Map< String, String > featureShortNames = model.getFeatureModel().getSpotFeatureShortNames();
		final Map< String, String > featureUnits = new HashMap<>();
		for ( final String feature : features )
		{
			final Dimension dimension = model.getFeatureModel().getSpotFeatureDimensions().get( feature );
			final String units = TMUtils.getUnitsFor( dimension, model.getSpaceUnits(), model.getTimeUnits() );
			featureUnits.put( feature, units );
		}
		final Map< String, Boolean > isInts = model.getFeatureModel().getSpotFeatureIsInt();
		final Map< String, String > infoTexts = new HashMap<>();
		final Function< Spot, String > labelGenerator = spot -> spot.getName();
		final BiConsumer< Spot, String > labelSetter = ( spot, label ) -> spot.setName( label );

		/*
		 * Feature provider. We add a fake one to show the spot ID.
		 */
		final String SPOT_ID = "ID";
		features.add( 0, SPOT_ID );
		featureNames.put( SPOT_ID, "Spot ID" );
		featureShortNames.put( SPOT_ID, "Spot ID" );
		featureUnits.put( SPOT_ID, "" );
		isInts.put( SPOT_ID, Boolean.TRUE );
		infoTexts.put( SPOT_ID, "The id of the spot." );

		/*
		 * Feature provider. We add a fake one to show the spot *track* ID.
		 */
		final String TRACK_ID = "TRACK_ID";
		features.add( 1, TRACK_ID );
		featureNames.put( TRACK_ID, "Track ID" );
		featureShortNames.put( TRACK_ID, "Track ID" );
		featureUnits.put( TRACK_ID, "" );
		isInts.put( TRACK_ID, Boolean.TRUE );
		infoTexts.put( TRACK_ID, "The id of the track this spot belongs to." );

		final BiFunction< Spot, String, Double > featureFun = ( spot, feature ) -> {
			if ( feature.equals( TRACK_ID ) )
			{
				final Integer trackID = model.getTrackModel().trackIDOf( spot );
				return trackID == null ? null : trackID.doubleValue();
			}
			else if ( feature.equals( SPOT_ID ) )
				return ( double ) spot.ID();

			return spot.getFeature( feature );
		};

		final BiConsumer< Spot, Color > colorSetter =
				( spot, color ) -> spot.putFeature( ManualSpotColorAnalyzerFactory.FEATURE, Double.valueOf( color.getRGB() ) );

		final Supplier< FeatureColorGenerator< Spot > > coloring =
				() -> FeatureUtils.createSpotColorGenerator( model, ds );

		final TablePanel< Spot > table =
				new TablePanel<>(
						objects,
						features,
						featureFun,
						featureNames,
						featureShortNames,
						featureUnits,
						isInts,
						infoTexts,
						coloring,
						labelGenerator,
						labelSetter,
						ManualSpotColorAnalyzerFactory.FEATURE,
						colorSetter );
		return table;
	}

	@Override
	public void render()
	{
		setLocationRelativeTo( null );
		setVisible( true );
	}

	@Override
	public void refresh()
	{
		repaint();
	}

	@Override
	public void modelChanged( final ModelChangeEvent event )
	{
		if ( event.getEventID() == ModelChangeEvent.FEATURES_COMPUTED )
		{
			refresh();
			return;
		}

		final List< Spot > spots = new ArrayList<>();
		for ( final Integer trackID : model.getTrackModel().unsortedTrackIDs( true ) )
			spots.addAll( model.getTrackModel().trackSpots( trackID ) );
		spotTable.setObjects( spots );

		final List< DefaultWeightedEdge > edges = new ArrayList<>();
		for ( final Integer trackID : model.getTrackModel().unsortedTrackIDs( true ) )
			edges.addAll( model.getTrackModel().trackEdges( trackID ) );
		edgeTable.setObjects( edges );

		final List< Integer > trackIDs = new ArrayList<>( model.getTrackModel().trackIDs( true ) );
		trackTable.setObjects( trackIDs );

		refresh();
	}

	/*
	 * Forward selection model changes to the tables.
	 */
	@Override
	public void selectionChanged( final SelectionChangeEvent event )
	{
		if ( ignoreSelectionChange.get() )
			return;
		ignoreSelectionChange.set( true );

		// Vertices table.
		final Set< Spot > selectedVertices = selectionModel.getSpotSelection();
		final JTable vt = spotTable.getTable();
		vt.getSelectionModel().clearSelection();
		for ( final Spot spot : selectedVertices )
		{
			final int row = spotTable.getViewRowForObject( spot );
			vt.getSelectionModel().addSelectionInterval( row, row );
		}

		// Center on selection if we added one spot exactly
		final Map< Spot, Boolean > spotsAdded = event.getSpots();
		if ( spotsAdded != null && spotsAdded.size() == 1 )
		{
			final boolean added = spotsAdded.values().iterator().next();
			if ( added )
			{
				final Spot spot = spotsAdded.keySet().iterator().next();
				centerViewOn( spot );
			}
		}

		// Edges table.
		final Set< DefaultWeightedEdge > selectedEdges = selectionModel.getEdgeSelection();
		final JTable et = edgeTable.getTable();
		et.getSelectionModel().clearSelection();
		for ( final DefaultWeightedEdge e : selectedEdges )
		{
			final int row = edgeTable.getViewRowForObject( e );
			et.getSelectionModel().addSelectionInterval( row, row );
		}

		// Center on selection if we added one edge exactly
		final Map< DefaultWeightedEdge, Boolean > edgesAdded = event.getEdges();
		if ( edgesAdded != null && edgesAdded.size() == 1 )
		{
			final boolean added = edgesAdded.values().iterator().next();
			if ( added )
			{
				final DefaultWeightedEdge edge = edgesAdded.keySet().iterator().next();
				centerViewOn( edge );
			}
		}

		refresh();
		ignoreSelectionChange.set( false );
	}

	public void centerViewOn( final DefaultWeightedEdge edge )
	{
		edgeTable.scrollToObject( edge );
	}

	@Override
	public void centerViewOn( final Spot spot )
	{
		spotTable.scrollToObject( spot );
	}

	@Override
	public Model getModel()
	{
		return model;
	}

	@Override
	public String getKey()
	{
		return KEY;
	}

	@Override
	public void clear()
	{}

	public TablePanel< Spot > getSpotTable()
	{
		return spotTable;
	}

	public TablePanel< DefaultWeightedEdge > getEdgeTable()
	{
		return edgeTable;
	}

	public TablePanel< Integer > getTrackTable()
	{
		return trackTable;
	}

	/**
	 * Forward spot table selection to selection model.
	 */
	private final class SpotTableSelectionListener implements ListSelectionListener
	{

		@Override
		public void valueChanged( final ListSelectionEvent event )
		{
			if ( event.getValueIsAdjusting() || ignoreSelectionChange.get() )
				return;

			ignoreSelectionChange.set( true );

			final int[] selectedRows = spotTable.getTable().getSelectedRows();
			final List< Spot > toSelect = new ArrayList<>( selectedRows.length );
			for ( final int row : selectedRows )
				toSelect.add( spotTable.getObjectForViewRow( row ) );

			selectionModel.clearSelection();
			selectionModel.addSpotToSelection( toSelect );
			refresh();

			ignoreSelectionChange.set( false );
		}
	}

	/**
	 * Forward edge table selection to selection model.
	 */
	private final class EdgeTableSelectionListener implements ListSelectionListener
	{

		@Override
		public void valueChanged( final ListSelectionEvent event )
		{
			if ( event.getValueIsAdjusting() || ignoreSelectionChange.get() )
				return;

			ignoreSelectionChange.set( true );

			final int[] selectedRows = edgeTable.getTable().getSelectedRows();
			final List< DefaultWeightedEdge > toSelect = new ArrayList<>( selectedRows.length );
			for ( final int row : selectedRows )
				toSelect.add( edgeTable.getObjectForViewRow( row ) );

			selectionModel.clearSelection();
			selectionModel.addEdgeToSelection( toSelect );
			refresh();

			ignoreSelectionChange.set( false );
		}
	}

	/**
	 * Forward track table selection to selection model.
	 */
	private class TrackTableSelectionListener implements ListSelectionListener
	{

		@Override
		public void valueChanged( final ListSelectionEvent event )
		{
			if ( event.getValueIsAdjusting() || ignoreSelectionChange.get() )
				return;

			ignoreSelectionChange.set( true );

			final Set< Spot > spots = new HashSet<>();
			final Set< DefaultWeightedEdge > edges = new HashSet<>();
			final int[] selectedRows = trackTable.getTable().getSelectedRows();
			for ( final int row : selectedRows )
			{
				final Integer trackID = trackTable.getObjectForViewRow( row );
				spots.addAll( model.getTrackModel().trackSpots( trackID ) );
				edges.addAll( model.getTrackModel().trackEdges( trackID ) );
			}
			selectionModel.clearSelection();
			selectionModel.addSpotToSelection( spots );
			selectionModel.addEdgeToSelection( edges );

			refresh();

			ignoreSelectionChange.set( false );

		}
	}
}
