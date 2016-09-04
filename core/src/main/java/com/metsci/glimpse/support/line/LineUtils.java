package com.metsci.glimpse.support.line;

import static com.jogamp.common.nio.Buffers.SIZEOF_FLOAT;
import static java.lang.Math.sqrt;
import static javax.media.opengl.GL.GL_ARRAY_BUFFER;
import static javax.media.opengl.GL.GL_BLEND;
import static javax.media.opengl.GL.GL_MAP_UNSYNCHRONIZED_BIT;
import static javax.media.opengl.GL.GL_MAP_WRITE_BIT;
import static javax.media.opengl.GL.GL_ONE;
import static javax.media.opengl.GL.GL_ONE_MINUS_SRC_ALPHA;
import static javax.media.opengl.GL.GL_SRC_ALPHA;
import static javax.media.opengl.GL2ES2.GL_STREAM_DRAW;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import javax.media.opengl.GL;

import com.metsci.glimpse.axis.Axis2D;

public class LineUtils
{

    public static void enableStandardBlending( GL gl )
    {
        gl.glBlendFuncSeparate( GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA );
        gl.glEnable( GL_BLEND );
    }

    public static double distance( double x0, double y0, double x1, double y1 )
    {
        double dx = x1 - x0;
        double dy = y1 - y0;
        return sqrt( dx*dx + dy*dy );
    }

    public static double distance( double x0, double y0, double x1, double y1, double ppvAspectRatio )
    {
        double dx = x1 - x0;
        double dy = ( y1 - y0 ) / ppvAspectRatio;
        return sqrt( dx*dx + dy*dy );
    }

    public static double ppvAspectRatio( Axis2D axis )
    {
        return ( axis.getAxisX( ).getPixelsPerValue( ) / axis.getAxisY( ).getPixelsPerValue( ) );
    }

    public static void put1f( FloatBuffer buffer, double a )
    {
        buffer.put( ( float ) a );
    }

    public static void put2f( FloatBuffer buffer, double a, double b )
    {
        buffer.put( ( float ) a )
              .put( ( float ) b );
    }

    public static FloatBuffer reallocFloatVbo( GL gl, int vbo, long numFloats, int usage )
    {
        return reallocVbo( gl, vbo, numFloats * SIZEOF_FLOAT, usage ).asFloatBuffer( );
    }

    public static ByteBuffer reallocVbo( GL gl, int vbo, long numBytes, int usage )
    {
        gl.glBindBuffer( GL_ARRAY_BUFFER, vbo );
        gl.glBufferData( GL_ARRAY_BUFFER, numBytes, null, GL_STREAM_DRAW );
        return gl.glMapBufferRange( GL_ARRAY_BUFFER, 0, numBytes, GL_MAP_WRITE_BIT | GL_MAP_UNSYNCHRONIZED_BIT );
    }

    public static void unmapVbo( GL gl, int vbo )
    {
        gl.glBindBuffer( GL_ARRAY_BUFFER, vbo );
        gl.glUnmapBuffer( GL_ARRAY_BUFFER );
    }
}
