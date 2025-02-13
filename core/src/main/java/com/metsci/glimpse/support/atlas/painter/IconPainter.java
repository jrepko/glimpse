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
package com.metsci.glimpse.support.atlas.painter;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL3;
import com.jogamp.opengl.GLContext;

import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.util.texture.TextureCoords;
import com.metsci.glimpse.axis.Axis1D;
import com.metsci.glimpse.axis.Axis2D;
import com.metsci.glimpse.context.GlimpseBounds;
import com.metsci.glimpse.context.GlimpseContext;
import com.metsci.glimpse.context.GlimpseTargetStack;
import com.metsci.glimpse.context.TargetStackUtil;
import com.metsci.glimpse.event.mouse.GlimpseMouseEvent;
import com.metsci.glimpse.event.mouse.GlimpseMouseMotionListener;
import com.metsci.glimpse.gl.GLSimpleFrameBufferObject;
import com.metsci.glimpse.gl.util.GLErrorUtils;
import com.metsci.glimpse.gl.util.GLUtils;
import com.metsci.glimpse.layout.GlimpseLayout;
import com.metsci.glimpse.painter.base.GlimpsePainterBase;
import com.metsci.glimpse.support.atlas.TextureAtlas;
import com.metsci.glimpse.support.atlas.shader.IconShader;
import com.metsci.glimpse.support.atlas.support.ImageData;
import com.metsci.glimpse.support.atlas.support.TextureAtlasUpdateListener;
import com.metsci.glimpse.support.selection.SpatialSelectionListener;

/**
 * A painter for efficiently painting large numbers of fixed pixel size icons at
 * fixed locations in data (axis) space.
 *
 * @author ulman
 */
//TODO: GLFloatBuffer dirties the entire array whenever a change is made, this means that
//      the addition of a single icon to the group causes the entire group to be pushed
//      to VRAM again. Either GLFloatBuffer needs to be smarter and use glSubBuffer or we
//      need to do things manually with FloatBuffers.
//
//TODO: Picking could also be done without OpenGL using the quadtree that GLFloatBuffer2D
//      provides. However, this might be slower and take up more memory with lots of icons.
//      It also doesn't easily handle not picking on transparent parts of icons.
//
//TODO: The problem with the current color-based picking approach is that it really only handles
//      picks at one location at a time (we could draw to a larger offscreen buffer,
//      or draw multiple times for each pick/click, not sure which would be faster).
//      Most of the complications spring from the possibility of painter retargeting.
public class IconPainter extends GlimpsePainterBase
{
    private static final Logger logger = Logger.getLogger( IconPainter.class.getName( ) );

    private static final int DEFAULT_INITIAL_GROUP_SIZE = 10;
    private static final float DEFAULT_GROWTH_FACTOR = 1.6f;

    private static final int COMPONENTS_PER_COLOR = 4;
    private static final int WIDTH_BUFFER = 5;
    private static final int HEIGHT_BUFFER = 5;

    protected int initialGroupSize;

    // shader fields
    protected IconShader shader;

    // map from group key (which can be anything) to internal icon group data
    protected Map<Object, IconGroup> iconGroupMap;

    protected Map<TextureAtlas, Set<IconGroup>> iconGroupsByAtlas;
    protected Map<TextureAtlas, TextureAtlasUpdateListener> atlasListeners;

    // fields related to picking support
    protected ByteBuffer pickResultBuffer;
    protected GLSimpleFrameBufferObject pickFrameBuffer;
    protected boolean pickSupportEnabled = false;
    protected GlimpseMouseMotionListener pickMouseListener;
    protected GlimpseLayout pickTarget;
    protected GlimpseMouseEvent pickMouseEvent;
    protected Collection<PickResult> pickResults;
    protected List<SpatialSelectionListener<PickResult>> pickListeners;
    protected Executor pickNotificationThread;

    //@formatter:off
    public IconPainter( int initialGroupSize, boolean enablePicking )
    {
        this.shader = new IconShader( 0, enablePicking );

        this.iconGroupMap = new HashMap<>( );
        this.iconGroupsByAtlas = new LinkedHashMap<>( );
        this.atlasListeners = new HashMap<>( );

        this.pickSupportEnabled = enablePicking;
        this.pickResultBuffer = Buffers.newDirectByteBuffer( Buffers.SIZEOF_BYTE * COMPONENTS_PER_COLOR * ( WIDTH_BUFFER * 2 + 1 ) * ( HEIGHT_BUFFER * 2 + 1 ) );
        this.pickListeners = new CopyOnWriteArrayList<SpatialSelectionListener<PickResult>>( );
        this.pickNotificationThread = Executors.newSingleThreadExecutor( );

        this.initialGroupSize = initialGroupSize;
    }
    //@formatter:on

    public IconPainter( )
    {
        this( DEFAULT_INITIAL_GROUP_SIZE, false );
    }

    public void addSpatialSelectionListener( SpatialSelectionListener<PickResult> listener )
    {
        this.pickListeners.add( listener );
    }

    public void removeSpatialSelectionListener( SpatialSelectionListener<PickResult> listener )
    {
        this.pickListeners.remove( listener );
    }

    /**
     * Indicates whether picking support is enabled for this painter. If true, registered
     * SpatialSelectionListener will be notified when the mouse is near an icon.
     */
    public boolean isPickingEnabled( )
    {
        this.painterLock.lock( );
        try
        {
            return this.pickSupportEnabled;
        }
        finally
        {
            this.painterLock.unlock( );
        }
    }

    /**
     * Enables or disables picking support. If enabled, registered
     * SpatialSelectionListener will be notified when the mouse is near an icon.
     *
     * @param enable
     */
    //TODO: allowing picking against only a single GlimpseLayout at a time runs counter
    //      to the painter retargeting changes.
    //      this could be extended to allow multiple targets, but that would increase the
    //      workload/complexity of the pickTo() method.
    public void setPickingEnabled( GlimpseLayout layout )
    {
        this.painterLock.lock( );
        try
        {
            if ( this.pickMouseListener != null && this.pickTarget != null )
            {
                this.pickTarget.removeGlimpseMouseMotionListener( this.pickMouseListener );
            }

            this.pickSupportEnabled = true;

            this.pickMouseListener = new GlimpseMouseMotionListener( )
            {
                @Override
                public void mouseMoved( GlimpseMouseEvent e )
                {
                    pickMouseEvent = e;
                }
            };

            this.pickTarget = layout;
            this.pickTarget.addGlimpseMouseMotionListener( this.pickMouseListener );
        }
        finally
        {
            this.painterLock.unlock( );
        }
    }

    public void setPickingDisabled( )
    {
        this.painterLock.lock( );
        try
        {
            this.pickSupportEnabled = false;

            if ( this.pickMouseListener != null && this.pickTarget != null )
            {
                this.pickTarget.removeGlimpseMouseMotionListener( this.pickMouseListener );
            }

            this.pickMouseEvent = null;
            this.pickTarget = null;
            this.pickMouseListener = null;
        }
        finally
        {
            this.painterLock.unlock( );
        }
    }

    /**
     * Sets the global scale factor for all icons (across all groups) rendered by this IconPainter.
     * A scale of 1.0 indicates that icons should be draw at their true width and height in pixels.
     *
     * @param scale the scale factor to apply to the width and height of icons
     */
    public void setGlobalScale( float scale )
    {
        this.shader.setGlobalScale( scale );
    }

    /**
     * @see #addIconGroup( Object, TextureAtlas, int )
     */
    public void addIconGroup( Object iconGroupId, TextureAtlas atlas )
    {
        addIconGroup( iconGroupId, atlas, initialGroupSize );
    }

    /**
     * Preallocates space for an icon group with the provided initial size. Useful if you know how many
     * icons you'll be painting ahead of time and want to avoid unnecessary data array copies.
     *
     * @param iconGroupId
     * @param initialSize
     */
    public void addIconGroup( Object iconGroupId, TextureAtlas atlas, int initialSize )
    {
        this.painterLock.lock( );
        try
        {
            IconGroup group = this.iconGroupMap.get( iconGroupId );
            if ( group == null )
            {
                group = new IconGroup( iconGroupId, atlas, initialSize );
                this.iconGroupMap.put( iconGroupId, group );
            }
            else
            {
                if ( atlas != group.getAtlas( ) )
                {
                    throw new RuntimeException( "An icon-group already exists for this id, but it is associated with a different atlas: icon-group-id = " + iconGroupId + ", existing-atlas = " + group.getAtlas( ) + ", new-atlas = " + atlas );
                }

                group.resize( initialSize, false );
            }

            Set<IconGroup> groups = this.iconGroupsByAtlas.get( atlas );
            if ( groups == null )
            {
                groups = new LinkedHashSet<IconGroup>( );
                this.iconGroupsByAtlas.put( atlas, groups );

                TextureAtlasUpdateListener atlasListener = createAtlasListener( atlas );
                atlas.addListener( atlasListener );
                atlasListeners.put( atlas, atlasListener );
            }
            groups.add( group );
        }
        finally
        {
            this.painterLock.unlock( );
        }
    }

    protected TextureAtlasUpdateListener createAtlasListener( final TextureAtlas atlas )
    {
        return new TextureAtlasUpdateListener( )
        {
            @Override
            public void reorganized( )
            {
                painterLock.lock( );
                try
                {
                    logger.info( "Texture Atlas was reorganized. Adjusting IconPainter with new texture coordinates." );

                    Set<IconGroup> groups = iconGroupsByAtlas.get( atlas );
                    if ( groups != null )
                    {
                        for ( IconGroup group : groups )
                        {
                            group.reloadTextureCoordinates( );
                        }
                    }
                }
                finally
                {
                    painterLock.unlock( );
                }
            }
        };
    }

    public void ensureIconGroupSize( Object iconGroupId, int minSize )
    {
        this.painterLock.lock( );
        try
        {
            IconGroup group = this.iconGroupMap.get( iconGroupId );
            group.resize( minSize, false );
        }
        finally
        {
            this.painterLock.unlock( );
        }
    }

    /**
     * @see #addIcon( Object, Object, float, float, float, float, float )
     */
    public void addIcon( Object iconGroupId, Object iconId, float positionX, float positionY, float rotation )
    {
        addIcon( iconGroupId, iconId, positionX, positionY, rotation, 1.0f );
    }

    /**
     * Adds the icon in the TextureAtlas referred to by iconId to iconGroupId at the provided coordinates.
     *
     * The icon must first be loaded using loadIcon( ). The iconGroupId can be any string, but efficient performance
     * from this painter will only be achieved with a small number of groups. Entire groups of icons can be
     * deleted or made invisible, but individual icons within groups cannot be removed or hidden.
     *
     * The scale parameter adjusts the size of the painted icon from the size stored in the TextureAtlas. A scale of
     * 1.0 indicates that the pixel size stored in the texture atlas should bed used unchanged.
     *
     * The iconGroupId must correspond to a group that has already been added with {@link #addIconGroup(Object, TextureAtlas, int)}
     * or {@link #addIconGroup(Object, TextureAtlas)}.
     *
     * @param iconGroupId an arbitrary string creating an association between this icon and others in the same group
     * @param iconId the identifier of an icon in the underlying texture atlas loaded using loadIcon()
     * @param positionX a position in axis space to place the icon at
     * @param positionY a position in axis space to place the icon at
     * @param rotation rotation around center point of icon (CCW radians; 0 implies no rotation)
     * @param scale a scale adjustment to the icon size
     */
    public void addIcon( Object iconGroupId, Object iconId, float positionX, float positionY, float rotation, float scale )
    {
        this.painterLock.lock( );
        try
        {
            IconGroup group = this.iconGroupMap.get( iconGroupId );
            group.addIcon( iconId, positionX, positionY, rotation, scale );
        }
        finally
        {
            this.painterLock.unlock( );
        }
    }

    //    /**
    //     * A bulk load method for adding many of the same type of icon at different locations simultaneously.
    //     *
    //     * @see addIcon( Object, Object, float, float, float )
    //     */
    //    public void addIcons( Object iconGroupId, Object iconId, float[] positionX, float[] positionY, float[] rotations )
    //    {
    //        addIcons( iconGroupId, iconId, 1.0f, positionX, positionY, rotations );
    //    }

    /**
     * @see #addIcon( Object, Object, float[], float[], float[] )
     */
    public void addIcons( Object iconGroupId, Object iconId, float[] positionX, float[] positionY, float[] rotation, float[] scale )
    {
        this.painterLock.lock( );
        try
        {
            IconGroup group = this.iconGroupMap.get( iconGroupId );
            group.addIcons( iconId, positionX, positionY, rotation, scale );
        }
        finally
        {
            this.painterLock.unlock( );
        }
    }

    /**
     * Loads icons into the IconPainter with x/y/rotation/scale quadruplets (one per icon) packed into a single float[] array.
     *
     * @see #addIcon( Object, Object, float[] )
     */
    public void addIcons( Object iconGroupId, Object iconId, float[] positions )
    {
        this.painterLock.lock( );
        try
        {
            IconGroup group = this.iconGroupMap.get( iconGroupId );
            group.addIcons( iconId, positions );
        }
        finally
        {
            this.painterLock.unlock( );
        }
    }

    /**
     * Loads icons into the IconPainter with x/y/rot/scale interleaved in a single FloatBuffer in the same
     * manner as {@link #addIcons( Object, Object, float, float[] )}. The offset provides the index of the first
     * x coordinate to load into the painter and the vertex count provides the total number of x/y/rot/scale quadruplets
     * to read from the FloatBuffer.
     */
    public void addIcons( Object iconGroupId, Object iconId, FloatBuffer positions, int offset, int vertexCount )
    {
        this.painterLock.lock( );
        try
        {
            IconGroup group = this.iconGroupMap.get( iconGroupId );
            group.addIcons( iconId, positions, offset, vertexCount );
        }
        finally
        {
            this.painterLock.unlock( );
        }
    }

    /**
     * Remove all the icons from the provided group.
     *
     * @param iconGroupId
     */
    public void removeIconGroup( Object iconGroupId )
    {
        this.painterLock.lock( );
        try
        {
            IconGroup group = this.iconGroupMap.remove( iconGroupId );
            if ( group == null ) return;

            group.dispose( );

            TextureAtlas atlas = group.atlas;
            Set<IconGroup> groups = this.iconGroupsByAtlas.get( atlas );
            if ( groups == null ) return;

            groups.remove( group );
            if ( groups.isEmpty( ) )
            {
                this.iconGroupsByAtlas.remove( atlas );
                TextureAtlasUpdateListener atlasListener = atlasListeners.remove( atlas );
                atlas.removeListener( atlasListener );
            }
        }
        finally
        {
            this.painterLock.unlock( );
        }
    }

    /**
     * Toggles whether or not a particular icon group is displayed. Hiding an icon group will not remove
     * its underlying data.
     *
     * @param iconGroupId
     * @param show
     */
    public void showIconGroup( Object iconGroupId, boolean show )
    {
        this.painterLock.lock( );
        try
        {
            IconGroup group = this.iconGroupMap.get( iconGroupId );
            group.setVisible( show );
        }
        finally
        {
            this.painterLock.unlock( );
        }
    }

    /**
     * Sets the visibility of all of this painter's icon-groups, so that a group is visible if and
     * only if its ID is in the specified collection.
     *
     * Hiding an icon group will not remove its underlying data.
     *
     * @param iconGroupId
     * @param show
     */
    public void showOnlyIconGroups( Collection<? extends Object> iconGroupIds )
    {
        this.painterLock.lock( );
        try
        {
            for ( IconGroup group : iconGroupMap.values( ) )
            {
                group.setVisible( false );
            }

            for ( Object groupId : iconGroupIds )
            {
                IconGroup group = this.iconGroupMap.get( groupId );
                if ( group != null )
                {
                    group.setVisible( true );
                }
            }
        }
        finally
        {
            this.painterLock.unlock( );
        }
    }

    @Override
    public void doPaintTo( GlimpseContext context )
    {
        GL3 gl = getGL3( context );

        if ( this.pickSupportEnabled )
        {
            // allocate the offscreen pick buffer if it does not exist
            if ( this.pickFrameBuffer == null )
            {
                this.pickFrameBuffer = new GLSimpleFrameBufferObject( WIDTH_BUFFER * 2 + 1, HEIGHT_BUFFER * 2 + 1, context.getGLContext( ) );
            }

            pickIcons( context );
        }

        GLUtils.enableStandardBlending( gl );
        try
        {
            paintIcons( context );
        }
        finally
        {
            GLUtils.disableBlending( gl );
        }
    }

    public void paintIcons( GlimpseContext context )
    {
        GL3 gl = getGL3( context );
        GlimpseBounds bounds = getBounds( context );
        Axis2D axis = requireAxis2D( context );

        // in pick mode the pick color is drawn in place of non-transparent areas of the texture
        this.shader.setPickMode( false );
        this.shader.updateViewport( bounds );
        this.shader.setProjectionMatrix( axis );

        for ( Map.Entry<TextureAtlas, Set<IconGroup>> entry : this.iconGroupsByAtlas.entrySet( ) )
        {
            TextureAtlas atlas = entry.getKey( );
            Set<IconGroup> groups = entry.getValue( );
            if ( groups.isEmpty( ) ) continue;

            atlas.beginRendering( context );
            try
            {
                // draw each icon group, if it is visible
                for ( IconGroup group : groups )
                {
                    // add any icons waiting to be added to the group
                    // we do this here because texture coordinates might not
                    // be known until the atlas.beginRendering( ) call
                    group.addQueuedIcons( );

                    this.shader.setTexCoordData( group.getBufferTexCoords( ) );
                    this.shader.setPixelCoordData( group.getBufferPixelCoords( ) );
                    this.shader.setColorCoordData( group.getPickColorCoords( ) );
                    this.shader.setVertexData( group.getBufferIconPlacement( ) );

                    if ( !group.isVisible( ) || group.getCurrentSize( ) == 0 ) continue;

                    this.shader.useProgram( gl, true );
                    try
                    {
                        gl.glDrawArrays( GL3.GL_POINTS, 0, group.getCurrentSize( ) );
                    }
                    finally
                    {
                        this.shader.useProgram( gl, false );
                    }

                    GLErrorUtils.logGLError( logger, gl, String.format( "Trouble after IconPainter.glDrawArrays( ). Group Size: %d", group.getCurrentSize( ) ) );
                }
            }
            finally
            {
                atlas.endRendering( context );
            }
        }
    }

    protected void pickIcons( GlimpseContext context )
    {
        // check whether mouse has moved since last draw
        if ( this.pickMouseEvent == null ) return;

        GL3 gl = getGL3( context );
        GlimpseBounds bounds = getBounds( context );
        Axis2D axis = requireAxis2D( context );
        GLContext glContext = context.getGLContext( );

        Set<PickResult> pickedIcons = new HashSet<PickResult>( );

        // in pick mode the pick color is drawn in place of non-transparent areas of the texture
        this.shader.setPickMode( true );
        this.shader.updateViewport( WIDTH_BUFFER * 2 + 1, HEIGHT_BUFFER * 2 + 1 );
        this.setPickProjectionMatrix( bounds, axis, this.pickMouseEvent.getX( ), bounds.getHeight( ) - this.pickMouseEvent.getY( ) );

        this.pickFrameBuffer.bind( glContext );
        try
        {
            for ( Map.Entry<TextureAtlas, Set<IconGroup>> entry : this.iconGroupsByAtlas.entrySet( ) )
            {
                TextureAtlas atlas = entry.getKey( );
                Set<IconGroup> groups = entry.getValue( );
                if ( groups.isEmpty( ) ) continue;

                atlas.beginRendering( context );
                try
                {
                    // draw each icon group, if it is visible
                    for ( IconGroup group : groups )
                    {
                        if ( !group.isVisible( ) ) continue;

                        this.shader.setTexCoordData( group.getBufferTexCoords( ) );
                        this.shader.setPixelCoordData( group.getBufferPixelCoords( ) );
                        this.shader.setColorCoordData( group.getPickColorCoords( ) );
                        this.shader.setVertexData( group.getBufferIconPlacement( ) );

                        resetPickFrameBuffer( glContext );

                        this.shader.useProgram( gl, true );
                        try
                        {
                            gl.glDrawArrays( GL3.GL_POINTS, 0, group.getCurrentSize( ) );
                        }
                        finally
                        {
                            this.shader.useProgram( gl, false );
                        }

                        checkPickFrameBuffer( context, group, pickedIcons );
                    }
                }
                finally
                {
                    atlas.endRendering( context );
                }
            }
        }
        finally
        {
            this.pickFrameBuffer.unbind( glContext );
        }

        notifySpatialSelectionListeners( pickedIcons );
    }

    // set the frame buffer background to transparent (which we will interpret
    // as no icon picked)
    protected void resetPickFrameBuffer( GLContext glContext )
    {
        GL gl = glContext.getGL( );

        gl.glClearColor( 0.0f, 0.0f, 0.0f, 0.0f );
        gl.glClear( GL3.GL_COLOR_BUFFER_BIT );

    }

    // set the orthographic projection to center on the WIDTH_BUFFER x HEIGHT_BUFFER square of pixels
    // around the click location
    // these are the only pixels which will be rendered into the small, offscreen pick buffer
    protected void setPickProjectionMatrix( GlimpseBounds bounds, Axis2D axis, int clickX, int clickY )
    {
        Axis1D axisX = axis.getAxisX( );
        Axis1D axisY = axis.getAxisY( );

        float minX = ( float ) axisX.screenPixelToValue( clickX - WIDTH_BUFFER );
        float maxX = ( float ) axisX.screenPixelToValue( clickX + WIDTH_BUFFER + 1 );

        float minY = ( float ) axisY.screenPixelToValue( clickY - HEIGHT_BUFFER );
        float maxY = ( float ) axisY.screenPixelToValue( clickY + HEIGHT_BUFFER + 1 );

        this.shader.setProjectionMatrix( minX, maxX, minY, maxY );
    }

    // look through the returned frame buffer and and append unique icons to the result set
    protected void checkPickFrameBuffer( GlimpseContext context, IconGroup group, Set<PickResult> resultSet )
    {
        GlimpseTargetStack stack = TargetStackUtil.newTargetStack( context.getTargetStack( ) );

        int width = WIDTH_BUFFER * 2 + 1;
        int height = HEIGHT_BUFFER * 2 + 1;

        context.getGL( ).glReadPixels( 0, 0, width, height, GL3.GL_RGBA, GL3.GL_UNSIGNED_BYTE, pickResultBuffer.rewind( ) );

        for ( int i = 0; i < width * height; i++ )
        {
            int r = pickResultBuffer.get( );
            int g = pickResultBuffer.get( );
            int b = pickResultBuffer.get( );
            int a = pickResultBuffer.get( );

            if ( a != 0 )
            {
                int iconIndex = ( s2u( r ) << 16 ) | ( s2u( g ) << 8 ) | s2u( b );

                Object groupId = group.getId( );
                Object iconId = group.getIconId( iconIndex );

                resultSet.add( new PickResult( groupId, iconId, iconIndex, stack ) );
            }
        }
    }

    protected int s2u( int b )
    {
        return b < 0 ? 256 + b : b;
    }

    protected void notifySpatialSelectionListeners( final Set<PickResult> resultSet )
    {
        // notify the listeners on a separate thread so that the notification and
        // listener response does not happen on the OpenGL drawing thread
        this.pickNotificationThread.execute( new Runnable( )
        {
            @Override
            public void run( )
            {
                for ( SpatialSelectionListener<PickResult> listener : pickListeners )
                {
                    listener.selectionChanged( resultSet );
                }
            }
        } );
    }

    @Override
    protected void doDispose( GlimpseContext context )
    {
        //XXX who should dispose of the texture atlas?
        //XXX us if we created it, someone else if it was passed in...?

        for ( IconGroup group : iconGroupMap.values( ) )
        {
            group.dispose( );
        }

        if ( pickFrameBuffer != null ) pickFrameBuffer.dispose( context.getGLContext( ) );
    }

    public class PickResult
    {
        private Object groupId;
        private Object iconId;
        private int iconIndex;

        private GlimpseTargetStack stack;

        public PickResult( Object groupId, Object iconId, int iconIndex, GlimpseTargetStack stack )
        {
            this.groupId = groupId;
            this.iconId = iconId;
            this.iconIndex = iconIndex;

            this.stack = stack;
        }

        public Object getGroupId( )
        {
            return groupId;
        }

        public Object getIconId( )
        {
            return iconId;
        }

        public int getIconIndex( )
        {
            return iconIndex;
        }

        /**
         * PickResults report a GlimpseTargetStack because they may be generated from
         * instances of the painter draw to different GlimpseTargets. A particular
         * listener might only care about icon selections from one of those GlimpseTargets.
         *
         * Other painters like {@link com.metsci.glimpse.painter.track.LineStripTrackPainter} have
         * similar issues. There is not a Glimpse-wide solution to this general problem
         * which was introduced as an ugly side-effect of the painter retargeting
         * changes made in Glimpse 0.8.
         */
        public GlimpseTargetStack getTargetStack( )
        {
            return stack;
        }

        @Override
        public String toString( )
        {
            return groupId + " " + iconId + " : " + iconIndex;
        }

        @Override
        public int hashCode( )
        {
            final int prime = 31;
            int result = 1;
            result = prime * result + getOuterType( ).hashCode( );
            result = prime * result + ( ( groupId == null ) ? 0 : groupId.hashCode( ) );
            result = prime * result + iconIndex;
            return result;
        }

        @Override
        public boolean equals( Object obj )
        {
            if ( this == obj ) return true;
            if ( obj == null ) return false;
            if ( getClass( ) != obj.getClass( ) ) return false;
            PickResult other = ( PickResult ) obj;
            if ( !getOuterType( ).equals( other.getOuterType( ) ) ) return false;
            if ( groupId == null )
            {
                if ( other.groupId != null ) return false;
            }
            else if ( !groupId.equals( other.groupId ) ) return false;
            if ( iconIndex != other.iconIndex ) return false;
            return true;
        }

        private IconPainter getOuterType( )
        {
            return IconPainter.this;
        }
    }

    private abstract class AddIcons
    {
        protected Object iconId;

        public abstract int getSize( );

        public abstract void addPlacementValues( IconGroup group );

        public void addIcons( IconGroup group )
        {
            final int size = getSize( );

            group.grow( size );

            final int currentSize = group.getCurrentSize( );

            final ImageData imageData = group.getAtlas( ).getImageData( iconId );
            final TextureCoords texData = imageData.getTextureCoordinates( );

            addPlacementValues( group );

            for ( int i = 0; i < size; i++ )
            {
                group.iconIds.add( iconId );
            }

            {
                FloatBuffer data = group.pixelCoordsValues;
                int length = 4;

                data.limit( currentSize * length );
                data.position( ( currentSize - size ) * length );

                float width = ( imageData.getWidth( ) + imageData.getBufferX( ) * 2 );
                float height = ( imageData.getHeight( ) + imageData.getBufferY( ) * 2 );
                float offsetX = ( imageData.getCenterX( ) + imageData.getBufferX( ) );
                float offsetY = ( imageData.getCenterY( ) + imageData.getBufferY( ) );

                for ( int i = 0; i < size; i++ )
                {
                    data.put( width );
                    data.put( height );
                    data.put( offsetX );
                    data.put( offsetY );
                }
            }

            {
                FloatBuffer data = group.texCoordsValues;
                int length = 4;

                data.limit( currentSize * length );
                data.position( ( currentSize - size ) * length );
                for ( int i = 0; i < size; i++ )
                {
                    data.put( texData.left( ) );
                    data.put( texData.right( ) );
                    data.put( texData.top( ) );
                    data.put( texData.bottom( ) );
                }
            }

            {
                ByteBuffer data = group.pickColorValues;
                int length = 3;
                // encode the index as r/g/b color components
                // this provides a maximum of 2^24 = 16 million
                // pickable icons per group (which is more than
                // we can display without performance degradation
                // anyway, so should be no problem)
                data.limit( currentSize * length );
                data.position( ( currentSize - size ) * length );
                for ( int i = 0; i < size; i++ )
                {
                    int index = currentSize - size + i;

                    byte r = ( byte ) ( ( index & 0x00ff0000 ) >> 16 );
                    byte g = ( byte ) ( ( index & 0x0000ff00 ) >> 8 );
                    byte b = ( byte ) ( ( index & 0x000000ff ) );

                    //System.out.printf( "%d %d %d%n", r, g, b );

                    data.put( r ).put( g ).put( b );
                }
            }
        }
    }

    private final class AddIconsSeparate extends AddIcons
    {
        float[] positionX;
        float[] positionY;
        float[] rotation;
        float[] scale;
        int size;

        public AddIconsSeparate( Object iconId, float[] positionX, float[] positionY, float[] rotation, float[] scale )
        {
            this.iconId = iconId;
            this.positionX = positionX;
            this.positionY = positionY;
            this.rotation = rotation;
            this.scale = scale;

            //XXX: Check length of rotation and scale arrays as well
            if ( positionX.length != positionY.length ) throw new IllegalArgumentException( String.format( "Size of positionX and positionY arrays must be identical. Found: %d and %d.", positionX.length, positionY.length ) );

            this.size = positionX.length;
        }

        @Override
        public int getSize( )
        {
            return size;
        }

        @Override
        public void addPlacementValues( final IconGroup group )
        {
            FloatBuffer data = group.iconPlacementValues;
            int length = 4;
            int currentSize = group.getCurrentSize( );

            data.limit( currentSize * length );
            data.position( ( currentSize - size ) * length );
            for ( int i = 0; i < size; i++ )
            {
                data.put( positionX[i] );
                data.put( positionY[i] );
                data.put( rotation[i] );
                data.put( scale[i] );
            }
        }
    }

    private final class AddIconsInterleaved extends AddIcons
    {
        float[] positions;
        int size;

        public AddIconsInterleaved( Object iconId, float[] positions )
        {
            this.iconId = iconId;
            this.positions = positions;

            if ( positions.length % 4 != 0 ) throw new IllegalArgumentException( String.format( "Size of position array must be a multiple of 4. Found: %d.", positions.length ) );

            this.size = positions.length / 4;
        }

        @Override
        public int getSize( )
        {
            return size;
        }

        @Override
        public void addPlacementValues( final IconGroup group )
        {
            FloatBuffer data = group.iconPlacementValues;
            int length = 4;
            int currentSize = group.getCurrentSize( );

            data.limit( currentSize * length );
            data.position( ( currentSize - size ) * length );
            data.put( positions, 0, size * length );
        }
    }

    private final class AddIconsBuffer extends AddIcons
    {
        FloatBuffer positions;
        int offset;
        int vertexCount;

        public AddIconsBuffer( Object iconId, FloatBuffer positions, int offset, int vertexCount )
        {
            this.iconId = iconId;
            this.positions = positions;
            this.offset = offset;
            this.vertexCount = vertexCount;
        }

        @Override
        public int getSize( )
        {
            return vertexCount;
        }

        @Override
        public void addPlacementValues( final IconGroup group )
        {
            FloatBuffer data = group.iconPlacementValues;
            int length = 4;
            int currentSize = group.getCurrentSize( );

            data.limit( currentSize * length );
            data.position( ( currentSize - vertexCount ) * length );

            int limit = positions.limit( );
            positions.limit( offset + vertexCount * length );
            positions.position( offset );
            data.put( positions );
            positions.limit( limit );
        }
    }

    private final class IconGroup
    {
        private Object id;

        private boolean visible;

        private int currentSize;
        private int maxSize;

        private TextureAtlas atlas;
        private List<Object> iconIds;

        private FloatBuffer iconPlacementValues;
        private FloatBuffer pixelCoordsValues;
        private FloatBuffer texCoordsValues;
        private ByteBuffer pickColorValues;

        private Collection<AddIcons> addQueue;

        public IconGroup( Object id, TextureAtlas atlas, int initialIconSpace )
        {
            this.id = id;

            this.visible = true;

            this.atlas = atlas;
            this.iconIds = new ArrayList<Object>( );

            this.iconPlacementValues = FloatBuffer.allocate( initialIconSpace * 4 );
            this.pixelCoordsValues = FloatBuffer.allocate( initialIconSpace * 4 );
            this.texCoordsValues = FloatBuffer.allocate( initialIconSpace * 4 );
            this.pickColorValues = ByteBuffer.allocate( initialIconSpace * 3 );

            this.addQueue = new LinkedList<AddIcons>( );

            this.currentSize = 0;
            this.maxSize = initialIconSpace;
        }

        public final Object getId( )
        {
            return this.id;
        }

        public final int getCurrentSize( )
        {
            return this.currentSize;
        }

        public final void setVisible( boolean visible )
        {
            this.visible = visible;
        }

        public final boolean isVisible( )
        {
            return this.visible;
        }

        public final TextureAtlas getAtlas( )
        {
            return this.atlas;
        }

        public final Object getIconId( int index )
        {
            return this.iconIds.get( index );
        }

        public final FloatBuffer getBufferIconPlacement( )
        {
            return this.iconPlacementValues;
        }

        public final FloatBuffer getBufferPixelCoords( )
        {
            return this.pixelCoordsValues;
        }

        public final FloatBuffer getBufferTexCoords( )
        {
            return this.texCoordsValues;
        }

        public final ByteBuffer getPickColorCoords( )
        {
            return this.pickColorValues;
        }

        public void addIcons( Object iconId, float[] positionX, float[] positionY, float rotation[], float[] scale )
        {
            this.addQueue.add( new AddIconsSeparate( iconId, positionX, positionY, rotation, scale ) );
        }

        public void addIcons( Object iconId, float[] positions )
        {
            this.addQueue.add( new AddIconsInterleaved( iconId, positions ) );
        }

        public void addIcons( Object iconId, FloatBuffer positions, int offset, int vertexCount )
        {
            this.addQueue.add( new AddIconsBuffer( iconId, positions, offset, vertexCount ) );
        }

        public void addIcon( Object iconId, final float positionX, final float positionY, final float rotation, float scale )
        {
            addIcons( iconId, new float[] { positionX }, new float[] { positionY }, new float[] { rotation }, new float[] { scale } );
        }

        public void addQueuedIcons( )
        {
            for ( AddIcons addIcons : addQueue )
            {
                addIcons.addIcons( this );
            }

            addQueue.clear( );
        }

        public void grow( int size )
        {
            this.currentSize = this.currentSize + size;
            if ( this.currentSize >= this.maxSize )
            {
                int newSize = Math.max( this.maxSize + size, ( int ) ( this.maxSize * DEFAULT_GROWTH_FACTOR ) );
                this.resize( newSize, false );
            }
        }

        public void resize( int newSize, boolean allowShrinking )
        {
            // nothing to do, the array is already larger than requested
            // and shrinking has been disallowed
            if ( newSize <= maxSize && !allowShrinking )
            {
                return;
            }

            // create new buffers of the new size
            FloatBuffer placementValues_temp = FloatBuffer.allocate( newSize * 4 );
            FloatBuffer pixelCoordsValues_temp = FloatBuffer.allocate( newSize * 4 );
            FloatBuffer texCoordsValues_temp = FloatBuffer.allocate( newSize * 4 );
            ByteBuffer pickColorValues_temp = ByteBuffer.allocate( newSize * 3 );

            // copy existing data to the new buffers
            this.iconPlacementValues.rewind( );
            placementValues_temp.put( this.iconPlacementValues );
            this.pixelCoordsValues.rewind( );
            pixelCoordsValues_temp.put( this.pixelCoordsValues );
            this.texCoordsValues.rewind( );
            texCoordsValues_temp.put( this.texCoordsValues );
            this.pickColorValues.rewind( );
            pickColorValues_temp.put( this.pickColorValues );

            // delete the old buffers
            this.dispose( );

            // use the new buffers in place of the old ones
            this.iconPlacementValues = placementValues_temp;
            this.pixelCoordsValues = pixelCoordsValues_temp;
            this.texCoordsValues = texCoordsValues_temp;
            this.pickColorValues = pickColorValues_temp;

            this.maxSize = newSize;
        }

        public void reloadTextureCoordinates( )
        {
            texCoordsValues.limit( currentSize * 4 );
            texCoordsValues.position( 0 );

            Object prevIconId = null;
            TextureCoords texData = null;

            for ( int i = 0; i < currentSize; i++ )
            {
                Object iconId = iconIds.get( i );

                // since looking up texture coordinates in the atlas involves acquiring
                // a lock, and often the same icon occurs multiple times in a row,
                // remember the coordinates of the last icon we added
                if ( !iconId.equals( prevIconId ) )
                {
                    ImageData imageData = atlas.getImageData( iconId );
                    texData = imageData.getTextureCoordinates( );
                }

                texCoordsValues.put( texData.left( ) );
                texCoordsValues.put( texData.right( ) );
                texCoordsValues.put( texData.top( ) );
                texCoordsValues.put( texData.bottom( ) );

                prevIconId = iconId;
            }
        }

        public void dispose( )
        {
            // does nothing after GL 3.0 refactoring
        }
    }
}
