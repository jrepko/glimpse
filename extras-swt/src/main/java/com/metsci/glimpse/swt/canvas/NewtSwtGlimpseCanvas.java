/*
 * Copyright (c) 2016, Metron, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of Metron, Inc. nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL METRON, INC. BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.metsci.glimpse.swt.canvas;

import static com.metsci.glimpse.util.logging.LoggerUtils.logWarning;

import java.awt.Dimension;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLContext;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.GLRunnable;

import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Shell;

import com.jogamp.newt.opengl.GLWindow;
import com.jogamp.newt.swt.NewtCanvasSWT;
import com.metsci.glimpse.canvas.LayoutManager;
import com.metsci.glimpse.canvas.NewtGlimpseCanvas;
import com.metsci.glimpse.canvas.NewtSwingGlimpseCanvas;
import com.metsci.glimpse.context.GlimpseBounds;
import com.metsci.glimpse.context.GlimpseContext;
import com.metsci.glimpse.context.GlimpseContextImpl;
import com.metsci.glimpse.context.GlimpseTarget;
import com.metsci.glimpse.context.GlimpseTargetStack;
import com.metsci.glimpse.event.mouse.newt.MouseWrapperNewt;
import com.metsci.glimpse.layout.GlimpseLayout;
import com.metsci.glimpse.painter.base.GlimpsePainter;
import com.metsci.glimpse.support.settings.LookAndFeel;

public class NewtSwtGlimpseCanvas extends Composite implements NewtGlimpseCanvas
{
    private static final Logger logger = Logger.getLogger( NewtSwtGlimpseCanvas.class.getName( ) );

    protected GLProfile glProfile;
    protected GLCapabilities glCapabilities;
    protected GLWindow glWindow;
    protected NewtCanvasSWT glCanvas;

    protected boolean isEventConsumer = true;
    protected boolean isEventGenerator = true;
    protected boolean isDestroyed = false;

    protected LayoutManager layoutManager;
    protected MouseWrapperNewt mouseHelper;
    protected GLEventListener glListener;

    protected List<GLRunnable> disposeListeners;

    protected Dimension dimension = new Dimension( 0, 0 );

    public NewtSwtGlimpseCanvas( Composite parent, GLProfile profile, int options )
    {
        super( parent, options );
        init( parent, profile, null, options );
    }

    public NewtSwtGlimpseCanvas( Composite parent, GLContext context, int options )
    {
        super( parent, options );
        init( parent, context.getGLDrawable( ).getGLProfile( ), context, options );
    }

    /**
     * @deprecated Use {@link #NewtSwtGlimpseCanvas(Composite, GLContext, int)} instead. The context implicitly provides a GLProfile.
     */
    @Deprecated
    public NewtSwtGlimpseCanvas( Composite parent, GLProfile glProfile, GLContext context, int options )
    {
        super( parent, options );
        init( parent, glProfile, context, options );
    }

    /**
     * @deprecated Use {@link #NewtSwtGlimpseCanvas(Composite, GLContext, int)} instead. The context implicitly provides a GLProfile.
     */
    @Deprecated
    public NewtSwtGlimpseCanvas( Composite parent, String profile, GLContext context, int options )
    {
        this( parent, GLProfile.get( profile ), context, options );
    }

    public void init( Composite parent, GLProfile glProfile, GLContext context, int options )
    {
        this.glProfile = glProfile;
        this.glCapabilities = new GLCapabilities( glProfile );

        this.glWindow = GLWindow.create( glCapabilities );
        if ( context != null ) this.glWindow.setSharedContext( context );
        this.glListener = createGLEventListener( );
        this.glWindow.addGLEventListener( this.glListener );

        FillLayout layout = new FillLayout( );
        this.setLayout( layout );

        this.glCanvas = new NewtCanvasSWT( this, options, glWindow )
        {
            @Override
            public void setBounds( int x, int y, int width, int height )
            {
                //do not allow a size of 0,0, because NEWT window becomes invisible
                super.setBounds( x, y, Math.max( 1, width ), Math.max( 1, height ) );
            }
        };

        this.glWindow.addGLEventListener( createGLEventListener( ) );

        this.layoutManager = new LayoutManager( );

        this.mouseHelper = new MouseWrapperNewt( this );
        this.glWindow.addMouseListener( this.mouseHelper );

        this.isDestroyed = false;

        this.disposeListeners = new CopyOnWriteArrayList<GLRunnable>( );
    }

    public void addDisposeListener( final Shell shell, final GLAutoDrawable sharedContextSource )
    {
        // Removing the canvas from the frame may prevent X11 errors (see http://tinyurl.com/m4rnuvf)
        // This listener must be added before adding the SwingGlimpseCanvas to
        // the frame because NEWTGLCanvas adds its own WindowListener and this WindowListener must
        // receive the WindowEvent first.
        shell.addDisposeListener( new DisposeListener( )
        {
            @Override
            public void widgetDisposed( DisposeEvent e )
            {
                // dispose of resources associated with the canvas
                disposeAttached( );

                // destroy the source of the shared glContext
                sharedContextSource.destroy( );
            }
        } );
    }

    private GLEventListener createGLEventListener( )
    {
        return new GLEventListener( )
        {
            @Override
            public void init( GLAutoDrawable drawable )
            {
                try
                {
                    GL gl = drawable.getGL( );
                    gl.setSwapInterval( 0 );
                }
                catch ( Exception e )
                {
                    // without this, repaint rate is tied to screen refresh rate on some systems
                    // this doesn't work on some machines (Mac OSX in particular)
                    // but it's not a big deal if it fails
                    logWarning( logger, "Trouble in init.", e );
                }
            }

            @Override
            public void display( GLAutoDrawable drawable )
            {
                for ( GlimpseLayout layout : layoutManager.getLayoutList( ) )
                {
                    layout.paintTo( getGlimpseContext( ) );
                }
            }

            @Override
            public void reshape( GLAutoDrawable drawable, int x, int y, int width, int height )
            {
                float[] scale = getSurfaceScale( );
                dimension = new Dimension( (int)(width / scale[0]), (int)(height / scale[1]) );

                for ( GlimpseLayout layout : layoutManager.getLayoutList( ) )
                {
                    layout.layoutTo( getGlimpseContext( ) );
                }
            }

            @Override
            public void dispose( GLAutoDrawable drawable )
            {
                for ( GLRunnable runnable : disposeListeners )
                {
                    runnable.run( drawable );
                }
            }
        };
    }

    public NewtCanvasSWT getCanvas( )
    {
        return glCanvas;
    }

    @Override
    public GLProfile getGLProfile( )
    {
        return this.glProfile;
    }

    @Override
    public GLAutoDrawable getGLDrawable( )
    {
        return glWindow;
    }

    @Override
    public GLWindow getGLWindow( )
    {
        return glWindow;
    }

    @Override
    public GlimpseContext getGlimpseContext( )
    {
        return new GlimpseContextImpl( this );
    }

    @Override
    public void setLookAndFeel( LookAndFeel laf )
    {
        for ( GlimpseTarget target : this.layoutManager.getLayoutList( ) )
        {
            target.setLookAndFeel( laf );
        }
    }

    @Override
    public void addLayout( GlimpseLayout layout )
    {
        this.layoutManager.addLayout( layout );
    }

    @Override
    public void addLayout( GlimpseLayout layout, int zOrder )
    {
        this.layoutManager.addLayout( layout, zOrder );
    }

    @Override
    public void setZOrder( GlimpseLayout layout, int zOrder )
    {
        this.layoutManager.setZOrder( layout, zOrder );
    }

    @Override
    public void removeLayout( GlimpseLayout layout )
    {
        this.layoutManager.removeLayout( layout );
    }

    @Override
    public void removeAllLayouts( )
    {
        this.layoutManager.removeAllLayouts( );
    }

    @SuppressWarnings( { "unchecked", "rawtypes" } )
    @Override
    public List<GlimpseTarget> getTargetChildren( )
    {
        // layoutManager returns an unmodifiable list, thus this cast is typesafe
        // (there is no way for the recipient of the List<GlimpseTarget> view to
        // add GlimpseTargets which are not GlimpseLayouts to the list)
        return ( List ) this.layoutManager.getLayoutList( );
    }

    public Dimension getDimension( )
    {
        return dimension;
    }

    @Override
    public GlimpseBounds getTargetBounds( GlimpseTargetStack stack )
    {
        return new GlimpseBounds( getDimension( ) );
    }

    @Override
    public GlimpseBounds getTargetBounds( )
    {
        return getTargetBounds( null );
    }

    @Override
    public void paint( )
    {
        this.glWindow.display( );
    }

    @Override
    public GLContext getGLContext( )
    {
        return this.glWindow.getContext( );
    }

    @Override
    public String toString( )
    {
        return NewtSwingGlimpseCanvas.class.getSimpleName( );
    }

    @Override
    public boolean isEventConsumer( )
    {
        return this.isEventConsumer;
    }

    @Override
    public void setEventConsumer( boolean consume )
    {
        this.isEventConsumer = consume;
    }

    @Override
    public boolean isEventGenerator( )
    {
        return this.isEventGenerator;
    }

    @Override
    public void setEventGenerator( boolean generate )
    {
        this.isEventGenerator = generate;
    }

    @Override
    public boolean isDestroyed( )
    {
        return this.isDestroyed;
    }

    @Override
    public void destroy( )
    {
        if ( !this.isDestroyed )
        {
            if ( this.glWindow != null ) this.glWindow.destroy( ); // dispose NEWT Window
            super.dispose( ); // dispose SWT Container
            this.isDestroyed = true;
        }
    }

    @Override
    public void addDisposeListener( GLRunnable runnable )
    {
        this.disposeListeners.add( runnable );
    }

    @Override
    public void dispose( )
    {
        // Stop the animator so that disposeAttached runs immediately in this thread
        // instead of on the animator thread. If this is not the case, then destroy( )
        // could run first and then the getGLDrawable( ).invoke( ) call will do nothing
        // because the window is already destroyed
        this.getGLDrawable( ).setAnimator( null );

        this.glWindow.removeMouseListener( this.mouseHelper );
        this.glWindow.removeGLEventListener( this.glListener );
        this.mouseHelper.dispose( );

        this.disposeAttached( );
        this.destroy( );
    }

    @Override
    public void disposeAttached( )
    {
        this.getGLDrawable( ).invoke( false, new GLRunnable( )
        {
            @Override
            public boolean run( GLAutoDrawable drawable )
            {
                for ( GlimpseLayout layout : layoutManager.getLayoutList( ) )
                {
                    layout.dispose( getGlimpseContext( ) );
                }

                // after layouts are disposed they should not be painted
                // so remove them from the canvas
                removeAllLayouts( );

                return true;
            }
        } );
    }

    @Override
    public void disposePainter( final GlimpsePainter painter )
    {
        this.getGLDrawable( ).invoke( false, new GLRunnable( )
        {
            @Override
            public boolean run( GLAutoDrawable drawable )
            {
                painter.dispose( getGlimpseContext( ) );
                return true;
            }
        } );
    }

    @Override
    public float[] getSurfaceScale( )
    {
        return glWindow.getRequestedSurfaceScale( new float[2] );
    }
}