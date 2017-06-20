package com.metsci.glimpse.support;

import static com.google.common.base.Objects.equal;
import static com.metsci.glimpse.support.DisposableUtils.onWindowClosing;
import static com.metsci.glimpse.support.FrameUtils.screenFracSize;
import static com.metsci.glimpse.util.GeneralUtils.array;
import static javax.media.opengl.GLProfile.GL3bc;
import static javax.swing.JOptionPane.VALUE_PROPERTY;
import static javax.swing.JOptionPane.WARNING_MESSAGE;
import static javax.swing.JOptionPane.YES_NO_OPTION;
import static javax.swing.WindowConstants.DISPOSE_ON_CLOSE;

import java.util.concurrent.atomic.AtomicBoolean;

import javax.media.opengl.GLAnimatorControl;
import javax.media.opengl.GLException;
import javax.media.opengl.GLProfile;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import com.metsci.glimpse.axis.Axis1D;
import com.metsci.glimpse.axis.listener.mouse.AxisMouseListener1D;
import com.metsci.glimpse.layout.GlimpseLayout;
import com.metsci.glimpse.painter.base.GlimpsePainter;
import com.metsci.glimpse.painter.decoration.BorderPainter;
import com.metsci.glimpse.painter.decoration.CrosshairPainter;
import com.metsci.glimpse.painter.decoration.GridPainter;
import com.metsci.glimpse.plot.MultiAxisPlot2D;
import com.metsci.glimpse.plot.MultiAxisPlot2D.AxisInfo;
import com.metsci.glimpse.support.swing.NewtSwingEDTGlimpseCanvas;
import com.metsci.glimpse.support.swing.SwingEDTAnimator;

/**
 * A collection of functions for quickly creating plots and showing them in windows.
 * <p>
 * The goal of this class is not to be polished, but to make it extremely easy to pop
 * up a quick data plot. This can be useful during development -- e.g. to examine data
 * for debugging purposes, or to plot timing results.
 * <p>
 * In terms of design aesthetics, the core module might not be the ideal place for this
 * class. However, keeping it in core allows it to be used without adding an extra
 * dependency, which is a practically significant improvement in ease of use.
 * <p>
 * @author hogye
 */
public class QuickUtils
{

    public static void requireSwingThread( )
    {
        if ( !SwingUtilities.isEventDispatchThread( ) )
        {
            throw new RuntimeException( "This operation is only allowed on the Swing/AWT event-dispatch thread" );
        }
    }

    public static GLProfile glProfileOrNull( String glProfileName )
    {
        try
        {
            return GLProfile.get( glProfileName );
        }
        catch ( GLException e )
        {
            return null;
        }
    }

    public static boolean showGLWarningDialog( String progName )
    {
        String dialogTitle = progName + " Warning";
        String text = "Graphics capabilities on this computer are not sufficient.\nYou can run the program anyway, but you may encounter rendering problems.";
        String runAnywayOption = "Run Anyway";
        String quitOption = "Quit";
        JOptionPane optionPane = new JOptionPane( text, WARNING_MESSAGE, YES_NO_OPTION, null, array( runAnywayOption, quitOption ), runAnywayOption );

        AtomicBoolean shouldRunAnyway = new AtomicBoolean( false );
        optionPane.addPropertyChangeListener( ( ev ) ->
        {
            if ( equal( ev.getPropertyName( ), VALUE_PROPERTY ) && equal( ev.getNewValue( ), runAnywayOption ) )
            {
                shouldRunAnyway.set( true );
            }
        } );

        // setVisible(true) blocks until we get an answer
        JDialog dialog = optionPane.createDialog( null, dialogTitle );
        dialog.setModal( true );
        dialog.setVisible( true );
        dialog.dispose( );

        return shouldRunAnyway.get( );
    }

    public static MultiAxisPlot2D quickXyPlot( GlimpsePainter... painters )
    {
        MultiAxisPlot2D plot = new MultiAxisPlot2D( );

        Axis1D xAxis = plot.getCenterAxisX( );
        Axis1D yAxis = plot.getCenterAxisY( );
        AxisInfo xAxisInfo = plot.createAxisBottom( "xAxis", xAxis, new AxisMouseListener1D( ) );
        AxisInfo yAxisInfo = plot.createAxisLeft( "yAxis", yAxis, new AxisMouseListener1D( ) );

        GridPainter gridPainter = new GridPainter( xAxisInfo.getTickHandler( ), yAxisInfo.getTickHandler( ) );

        CrosshairPainter crosshairPainter = new CrosshairPainter( );
        crosshairPainter.showSelectionBox( false );

        BorderPainter borderPainter = new BorderPainter( );

        plot.addPainter( gridPainter );
        for ( GlimpsePainter painter : painters )
        {
            plot.addPainter( painter );
        }
        plot.addPainter( crosshairPainter );
        plot.addPainter( borderPainter );

        return plot;
    }

    public static void quickGlimpseWindow( String progName, String glProfile, double screenFrac, GlimpseLayout layout )
    {
        requireSwingThread( );

        GLProfile profile = glProfileOrNull( GL3bc );
        if ( glProfile == null && !showGLWarningDialog( progName ) )
        {
            return;
        }

        NewtSwingEDTGlimpseCanvas canvas = new NewtSwingEDTGlimpseCanvas( profile );
        canvas.addLayout( layout );

        GLAnimatorControl animator = new SwingEDTAnimator( 60 );
        animator.add( canvas.getGLDrawable( ) );
        animator.start( );

        JFrame frame = new JFrame( );
        frame.setTitle( progName );
        frame.getContentPane( ).add( canvas );
        frame.setSize( screenFracSize( screenFrac ) );
        frame.setLocationRelativeTo( null );
        frame.setDefaultCloseOperation( DISPOSE_ON_CLOSE );
        frame.setVisible( true );

        onWindowClosing( frame, ( ev ) ->
        {
            animator.stop( );
            canvas.getCanvas( ).setNEWTChild( null );
            frame.getContentPane( ).remove( canvas );
            canvas.destroy( );
        } );
    }

}
