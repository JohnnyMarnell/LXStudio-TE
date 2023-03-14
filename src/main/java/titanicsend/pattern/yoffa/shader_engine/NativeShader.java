package titanicsend.pattern.yoffa.shader_engine;

import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.*;
import com.jogamp.opengl.GL4;
import com.jogamp.opengl.util.GLBuffers;
import com.jogamp.opengl.util.texture.Texture;
import com.jogamp.opengl.util.texture.TextureData;
import com.jogamp.opengl.util.texture.TextureIO;
import heronarts.lx.LX;
import heronarts.lx.color.LXColor;
import heronarts.lx.parameter.LXParameter;
import titanicsend.util.TE;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.*;
import java.util.HashMap;
import java.util.Map;
import Jama.Matrix;

import static com.jogamp.opengl.GL.*;
import static titanicsend.pattern.yoffa.shader_engine.UniformTypes.*;


//Technically we don't need to implement GLEventListener unless we plan on rendering on screen, but let's leave it
//for good practice
public class NativeShader implements GLEventListener {

    //we need to draw an object with a vertex shader to put our fragment shader on
    //literally just create a rectangle that takes up the whole screen to paint on
    //TODO currently we're only able to run one OpenGL pattern at a time. We need to split this into multiple
    //  rectangles so we can display multiple patterns in the frame.
    private static final float[] VERTICES = {
            1.0f, 1.0f, 0.0f,
            1.0f, -1.0f, 0.0f,
            -1.0f, -1.0f, 0.0f,
            -1.0f, 1.0f, 0.0f
    };

    //we are drawing with triangles, so we need two to make our rectangle
    private static final int[] INDICES = {
            0, 1, 2,
            2, 0, 3
    };

    private static final Map<Integer, Integer> INDEX_TO_GL_ENUM = Map.of(
            0, GL_TEXTURE0,
            1, GL_TEXTURE1,
            2, GL_TEXTURE2,
            3, GL_TEXTURE3); //stupid

    private static final String FRAGMENT_SHADER_TEMPLATE =
            ShaderUtils.loadResource("resources/shaders/framework/template.fs");
    private static final String SHADER_BODY_PLACEHOLDER = "{{%shader_body%}}";

    private final FragmentShader fragmentShader;
    private final int xResolution;
    private final int yResolution;

    private final FloatBuffer vertexBuffer;
    private final IntBuffer indexBuffer;
    int[] geometryBufferHandles = new int[2];

    private final Map<Integer, Texture> textures;
    int[] textureBufferHandle = new int[1];
    private int textureKey;
    boolean needAudioCleanup;
    private final Integer audioChannel;
    private ShaderProgram shaderProgram;
    private long startTime;

    ByteBuffer backBuffer;
    private int[][] snapshot;

    private ShaderOptions shaderOptions;
    private int alphaMask;

    private PatternControlData controlData;

    private final int audioTextureWidth;
    private final int audioTextureHeight;
    FloatBuffer audioTextureData;

    // map of user created uniforms.
    protected HashMap<String, UniformTypes> uniforms = null;

    public NativeShader(FragmentShader fragmentShader, int xResolution, int yResolution, ShaderOptions options) {
        this.xResolution = xResolution;
        this.yResolution = yResolution;
        this.fragmentShader = fragmentShader;
        this.vertexBuffer = Buffers.newDirectFloatBuffer(VERTICES.length);
        this.indexBuffer = Buffers.newDirectIntBuffer(INDICES.length);
        this.vertexBuffer.put(VERTICES);
        this.indexBuffer.put(INDICES);
        this.textures = new HashMap<>();
        this.textureKey = 0;
        this.controlData = null;
        this.audioChannel = fragmentShader.getAudioInputChannel();

        // gl-compatible buffer for reading offscreen surface to cpu memory
        this.backBuffer = GLBuffers.newDirectByteBuffer(xResolution * yResolution * 4);
        this.snapshot = new int[xResolution][yResolution];

        // set shader options
        this.shaderOptions = options;
        this.setAlphaMask(shaderOptions.getAlpha());

        this.audioTextureWidth = 512;
        this.audioTextureHeight = 2;
        this.audioTextureData = GLBuffers.newDirectFloatBuffer(audioTextureHeight * audioTextureWidth);
    }

    /**
     * Determines whether alpha values returned from the fragment shader will be used.  Can safely
     * be changed while the shader is running.
     *
     * @param b - true to enable the alpha channel for this shader, false to ignore it, discard
     *          whatever the shader does, and set alpha to full opacity
     */
    public void setAlphaMask(boolean b) {
        this.alphaMask = (b) ? 0 : 0xff;
    }

    @Override
    public void init(GLAutoDrawable glAutoDrawable) {
        glAutoDrawable.getContext().makeCurrent();
        GL4 gl4 = glAutoDrawable.getGL().getGL4();

        if (!isInitialized()) {
            initShaderProgram(gl4);
            downloadTextureFiles(fragmentShader);
            gl4.glUseProgram(shaderProgram.getProgramId());
        }

        startTime = System.currentTimeMillis();
    }

    // create a Texture object from audioTextureData
    private Texture createAudioTexture(GL4 gl4) {
        TextureData td = new TextureData(GLProfile.getGL4ES3(),
                GL4.GL_RGBA32F,
                audioTextureWidth,
                audioTextureHeight,
                0,
                GL4.GL_RED,
                GL4.GL_FLOAT,
                false, false, false,
                audioTextureData, null);

        Texture tex = new Texture(gl4, td);
        tex.setTexParameteri(gl4, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        tex.setTexParameteri(gl4, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        tex.setTexParameteri(gl4, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        tex.setTexParameteri(gl4, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        return tex;
    }


    public void cleanupGLHandles(GL4 gl4) {
        gl4.glDeleteBuffers(2, geometryBufferHandles, 0);
        if (needAudioCleanup) gl4.glDeleteTextures(1, textureBufferHandle, 0);
    }

    @Override
    public void display(GLAutoDrawable glAutoDrawable) {
        // switch to this shader's gl context and render
        glAutoDrawable.getContext().makeCurrent();
        GL4 gl4 = glAutoDrawable.getGL().getGL4();
        setUniforms(gl4);
        setUpCanvas(gl4);
        cleanupGLHandles(gl4);
        saveSnapshot(gl4, xResolution, yResolution);
    }

    private void saveSnapshot(GL4 gl4, int width, int height) {
        backBuffer.rewind();
        gl4.glReadBuffer(GL_BACK);
        gl4.glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, backBuffer);

        for (int h = 0; h < height; h++) {
            for (int w = 0; w < width; w++) {
                snapshot[w][h] = LXColor.rgba((backBuffer.get() & 0xff), (backBuffer.get() & 0xff),
                        backBuffer.get() & 0xff, alphaMask | (backBuffer.get() & 0xff));
            }
        }
    }

    private void setUpCanvas(GL4 gl4) {
        // Configure geometry buffers
        // TODO - needs post year-1 refactor for better GL context & handle management

        // vertices
        vertexBuffer.rewind();
        gl4.glGenBuffers(1, geometryBufferHandles, 0);
        gl4.glBindBuffer(GL_ARRAY_BUFFER, geometryBufferHandles[0]);
        gl4.glBufferData(GL_ARRAY_BUFFER, (long) vertexBuffer.capacity() * Float.BYTES, vertexBuffer, GL.GL_STATIC_DRAW);

        gl4.glVertexAttribPointer(shaderProgram.getShaderAttributeLocation(ShaderAttribute.POSITION),
                3, GL4.GL_FLOAT, false, 0, 0);
        gl4.glEnableVertexAttribArray(shaderProgram.getShaderAttributeLocation(ShaderAttribute.POSITION));

        // geometry built from vertices (triangles!)
        indexBuffer.rewind();
        gl4.glGenBuffers(1, geometryBufferHandles, 1);
        gl4.glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, geometryBufferHandles[1]);
        gl4.glBufferData(GL_ELEMENT_ARRAY_BUFFER, (long) indexBuffer.capacity() * Integer.BYTES, indexBuffer, GL.GL_STATIC_DRAW);

        gl4.glDrawElements(GL2.GL_TRIANGLES, INDICES.length, GL2.GL_UNSIGNED_INT, 0);

        gl4.glDisableVertexAttribArray(shaderProgram.getShaderAttributeLocation(ShaderAttribute.POSITION));
    }

    private void setStandardUniforms(PatternControlData ctl) {

        // set standard shadertoy-style uniforms
        setUniform("iTime", ctl.getTime());
        setUniform("iResolution", (float) xResolution, (float) yResolution);
        setUniform("iMouse", 0f, 0f, 0f, 0f);

        // TE standard audio uniforms
        setUniform("beat",ctl.getBeat());
        setUniform("sinPhaseBeat",ctl.getSinePhaseOnBeat());
        setUniform("bassLevel",ctl.getBassLevel());
        setUniform("trebleLevel",ctl.getTrebleLevel());

        // color-related uniforms
        float x, y, z;
        int color = ctl.getColor();
        x = (float) (0xff & LXColor.red(color)) / 255f;
        y = (float) (0xff & LXColor.green(color)) / 255f;
        z = (float) (0xff & LXColor.blue(color)) / 255f;
        setUniform("iColorRGB", x, y, z);

        x = LXColor.h(color) / 360f;
        y = LXColor.s(color) / 100f;
        z = LXColor.b(color)/ 100f;
        setUniform("iColorHSB",x,y,z);

        setUniform("iPalette", ctl.getCurrentPalette(), 3);

        // uniforms for common controls
        setUniform("iSpeed",ctl.getSpeed());
        setUniform("iScale",ctl.getSize());
        setUniform("iQuantity",ctl.getQuantity());
        setUniform("iTranslate",ctl.getXPos(),ctl.getYPos());
        setUniform("iRotationAngle",ctl.getRotationAngle());
        setUniform("iBrightness",ctl.getBrightness());
        setUniform("iWow1",ctl.getWow1());
        setUniform("iWow2",ctl.getWow2());
        setUniform("iWowTrigger",ctl.getWowTrigger());
    }

    private void setUniforms(GL4 gl4) {

        // set uniforms for standard controls and audio information
        setStandardUniforms(controlData);

        // if enabled, add all LX parameters as uniforms
        if (shaderOptions.getLXParameterUniforms()) {
            for (LXParameter customParameter : fragmentShader.getParameters()) {
                setUniform(customParameter.getLabel() + Uniforms.CUSTOM_SUFFIX, customParameter.getValuef());
            }
        }

        // add texture channels
        textureKey = 0;
        for (Map.Entry<Integer, Texture> textureInput : textures.entrySet()) {
            setUniform(Uniforms.CHANNEL + textureInput.getKey(), textureInput.getValue(), true);
        }

        // if enabled, set audio waveform and fft data as a 512x2 texture on the specified audio
        // channel if it's a shadertoy shader, or iChannel0 if it's a local shader.
        needAudioCleanup = false;
        if (audioChannel != null && shaderOptions.getWaveData()) {
            gl4.glActiveTexture(INDEX_TO_GL_ENUM.get(0));
            gl4.glEnable(GL_TEXTURE_2D);
            gl4.glGenTextures(1, textureBufferHandle, 0);
            gl4.glBindTexture(GL4.GL_TEXTURE_2D, textureBufferHandle[0]);

            // load frequency and waveform data into our texture, fft data in the first row,
            // normalized audio waveform data in the second.
            for (int n = 0; n < audioTextureWidth; n++) {
                audioTextureData.put(n, controlData.getFrequencyData(n));
                audioTextureData.put(n + audioTextureWidth, controlData.getWaveformData(n));
            }

            gl4.glTexImage2D(GL4.GL_TEXTURE_2D, 0, GL4.GL_R32F, audioTextureWidth, audioTextureHeight, 0, GL4.GL_RED, GL_FLOAT, audioTextureData);
            gl4.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            gl4.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            gl4.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            gl4.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            //TE.log("Adding texture iChannel%d",this.audioChannel);
            setUniform(Uniforms.CHANNEL + this.audioChannel, this.audioChannel);
            needAudioCleanup = true;
        }

        // hand the complete uniform list to OpenGL
        updateUniforms(gl4);
    }

    private void initShaderProgram(GL4 gl4) {
        File vertexShader = new File("resources/shaders/framework/default.vs");
        shaderProgram = new ShaderProgram();
        String shaderCode = FRAGMENT_SHADER_TEMPLATE.replace(SHADER_BODY_PLACEHOLDER, fragmentShader.getShaderBody());
        shaderProgram.init(gl4, vertexShader, shaderCode,
                fragmentShader.getShaderName(), fragmentShader.getShaderTimestamp());
        setUpCanvas(gl4);
    }

    private void downloadTextureFiles(FragmentShader fragmentShader) {
        for (Map.Entry<Integer, String> textureInput : fragmentShader.getChannelToTexture().entrySet()) {
            try {
                if (fragmentShader.hasRemoteTextures()) {
                    //TE.log("Remote Texture %s", textureInput.getValue());
                    URL url = new URL(textureInput.getValue());
                    textures.put(textureInput.getKey(), TextureIO.newTexture(url, false, null));
                } else {
                    File file = new File(textureInput.getValue());
                    //TE.log("File Texture %s", textureInput.getValue());
                    textures.put(textureInput.getKey(), TextureIO.newTexture(file, false));
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void dispose(GLAutoDrawable glAutoDrawable) {
        GL4 gl4 = glAutoDrawable.getGL().getGL4();
        shaderProgram.dispose(gl4);
    }

    @Override
    public void reshape(GLAutoDrawable glAutoDrawable, int x, int y, int width, int height) {
        //do nothing
    }

    public int[][] getSnapshot() {
        return snapshot;
    }

    public void updateAudioInfo(PatternControlData audioInfo) {
        this.controlData = audioInfo;
    }

    public void reset() {
        startTime = System.currentTimeMillis();
    }

    public boolean isInitialized() {
        return (shaderProgram != null) && (shaderProgram.isInitialized());
    }

    /*
     Generic shader uniform handler - based on processing.opengl.PShader (www.processing.org)
     The idea is that this mechanism can handle our normal shadertoy-like and LX engine generated
     uniforms, plus any other uniforms the user wants to use.  This makes it easier to do things like
     sending the current foreground color to the shader as a vec3.

     When doing this, THE PATTERN/SHADER AUTHOR IS RESPONSIBLE for seeing that the uniform names match
     in the Java pattern code and the shader.  Otherwise... nothing ... will happen.

     Sigh... most of this code consists of ten zillion overloaded methods to set uniforms of all the supported
     types. Oh yeah, and a couple of worker methods to manage the uniform list we're building.
     */

    // worker for adding user specified uniforms to our big list'o'uniforms
    protected void addUniform(String name, int type, Object value) {
        if (uniforms == null) {
            uniforms = new HashMap<>();
        }
        uniforms.put(name, new UniformTypes(type, value));
    }

    // parse uniform list and create necessary GL objects
    // TODO  still need support for general purpose textures.  Once that's done
    // TODO  we can use this subsystem for *all* uniforms and eliminate some complexity.
    // Note that array buffers passed in must be allocated to the exact appropriate size
    // you want. No allocating a big buffer, then partly filling it. GL is picky about this.
    protected void updateUniforms(GL4 gl4) {
        int[] v;
        float[] vf;
        IntBuffer vIArray;
        FloatBuffer vFArray;
        if (uniforms != null && 0 < uniforms.size()) {
            for (String name : uniforms.keySet()) {
                int loc = gl4.glGetUniformLocation(shaderProgram.getProgramId(), name);

                if (loc == -1) {
                    // LX.log("No uniform \"" + name + "\"  found in shader");
                    continue;
                }
                UniformTypes val = uniforms.get(name);

                switch (val.type) {
                    case INT1:
                        v = ((int[]) val.value);
                        gl4.glUniform1i(loc, v[0]);
                        break;
                    case INT2:
                        v = ((int[]) val.value);
                        gl4.glUniform2i(loc, v[0], v[1]);
                        break;
                    case INT3:
                        v = ((int[]) val.value);
                        gl4.glUniform3i(loc, v[0], v[1], v[2]);
                        break;
                    case INT4:
                        v = ((int[]) val.value);
                        gl4.glUniform4i(loc, v[0], v[1], v[2], v[3]);
                        break;
                    case FLOAT1:
                        vf = ((float[]) val.value);
                        gl4.glUniform1f(loc, vf[0]);
                        break;
                    case FLOAT2:
                        vf = ((float[]) val.value);
                        gl4.glUniform2f(loc, vf[0], vf[1]);
                        break;
                    case FLOAT3:
                        vf = ((float[]) val.value);
                        gl4.glUniform3f(loc, vf[0], vf[1], vf[2]);
                        break;
                    case FLOAT4:
                        vf = ((float[]) val.value);
                        gl4.glUniform4f(loc, vf[0], vf[1], vf[2], vf[3]);
                        break;
                    case INT1VEC:
                        vIArray = ((IntBuffer) val.value);
                        gl4.glUniform1iv(loc, vIArray.capacity(), vIArray);
                        break;
                    case INT2VEC:
                        vIArray = ((IntBuffer) val.value);
                        gl4.glUniform2iv(loc, vIArray.capacity() / 2, vIArray);
                        break;
                    case INT3VEC:
                        vIArray = ((IntBuffer) val.value);
                        gl4.glUniform3iv(loc, vIArray.capacity() / 3, vIArray);
                        break;
                    case INT4VEC:
                        vIArray = ((IntBuffer) val.value);
                        gl4.glUniform4iv(loc, vIArray.capacity() / 4, vIArray);
                        break;
                    case FLOAT1VEC:
                        vFArray = ((FloatBuffer) val.value);
                        gl4.glUniform1fv(loc, vFArray.capacity(), vFArray);
                        break;
                    case FLOAT2VEC:
                        vFArray = ((FloatBuffer) val.value);
                        gl4.glUniform2fv(loc, vFArray.capacity() / 2, vFArray);
                        break;
                    case FLOAT3VEC:
                        vFArray = ((FloatBuffer) val.value);
                        gl4.glUniform3fv(loc, vFArray.capacity() / 3, vFArray);
                        break;
                    case FLOAT4VEC:
                        vFArray = ((FloatBuffer) val.value);
                        gl4.glUniform4fv(loc, vFArray.capacity() / 4, vFArray);
                        break;
                    case MAT2:
                        vFArray = ((FloatBuffer) val.value);
                        gl4.glUniformMatrix2fv(loc, 1, true, vFArray);
                        break;
                    case MAT3:
                        vFArray = ((FloatBuffer) val.value);
                        gl4.glUniformMatrix3fv(loc, 1, true, vFArray);
                        break;
                    case MAT4:
                        vFArray = ((FloatBuffer) val.value);
                        gl4.glUniformMatrix4fv(loc, 1, true, vFArray);
                        break;
                    case SAMPLER2DSTATIC:
                    case SAMPLER2D:
                        Texture tex = ((Texture) val.value);
                        gl4.glActiveTexture(INDEX_TO_GL_ENUM.get(textureKey));
                        tex.enable(gl4);
                        tex.bind(gl4);
                        gl4.glUniform1i(loc, textureKey);
                        tex.disable(gl4);
                        textureKey++;
                        break;
                    default:
                        LX.log("Unsupported uniform type");
                        break;
                }
            }
            uniforms.clear();
        }
    }

    /**
     * setter -- single int
     */
    public void setUniform(String name, int x) {
        addUniform(name, INT1, new int[]{x});
    }

    /**
     * 2 element int array or ivec2
     */
    public void setUniform(String name, int x, int y) {
        addUniform(name, INT2, new int[]{x, y});
    }

    /**
     * 3 element int array or ivec3
     */
    public void setUniform(String name, int x, int y, int z) {
        addUniform(name, INT3, new int[]{x, y, z});
    }

    /**
     * 4 element int array or ivec4
     */
    public void setUniform(String name, int x, int y, int z, int w) {
        addUniform(name, UniformTypes.INT4, new int[]{x, y, z, w});
    }

    /**
     * single float
     */
    public void setUniform(String name, float x) {
        addUniform(name, UniformTypes.FLOAT1, new float[]{x});
    }

    /**
     * 2 element float array or vec2
     */
    public void setUniform(String name, float x, float y) {
        addUniform(name, UniformTypes.FLOAT2, new float[]{x, y});
    }

    /**
     * 3 element float array or vec3
     */
    public void setUniform(String name, float x, float y, float z) {
        addUniform(name, UniformTypes.FLOAT3, new float[]{x, y, z});
    }

    /**
     * 4 element float array or vec4
     */
    public void setUniform(String name, float x, float y, float z, float w) {
        addUniform(name, UniformTypes.FLOAT4, new float[]{x, y, z, w});
    }

    public void setUniform(String name, boolean x) {
        addUniform(name, INT1, new int[]{(x) ? 1 : 0});
    }

    public void setUniform(String name, boolean x, boolean y) {
        addUniform(name, INT2,
                new int[]{(x) ? 1 : 0, (y) ? 1 : 0});
    }


    /**
     * Create SAMPLER2D uniform from jogl Texture object - this prototype supports
     * both dynamic and static textures.  If you know your texture will
     * be changed on every frame, use setUniform(String name, Texture tex)
     * instead.
     * TODO - STATIC TEXTURES NOT YET IMPLEMENTED
     */
    public void setUniform(String name, Texture tex, boolean isStatic) {
        addUniform(name, UniformTypes.SAMPLER2D, tex);
    }

    /**
     * Create SAMPLER2D uniform from jogl Texture object - for dynamic textures
     * (that change every frame).   For static textures, use
     * setUniform(String name, Texture tex,boolean isStatic) instead.
     * TODO - static/dynamic textures not yet implemented.  All textures are treated
     * TODO - as dynamic and reloaded on every frame.
     */
    public void setUniform(String name, Texture tex) {
        addUniform(name, UniformTypes.SAMPLER2D, tex);
    }

    /**
     * @param columns number of coordinates per element, max 4
     */
    public void setUniform(String name, IntBuffer vec, int columns) {
        switch (columns) {
            case 1:
                addUniform(name, UniformTypes.INT1VEC, vec);
                break;
            case 2:
                addUniform(name, UniformTypes.INT2VEC, vec);
                break;
            case 3:
                addUniform(name, UniformTypes.INT3VEC, vec);
                break;
            case 4:
                addUniform(name, UniformTypes.INT4VEC, vec);
                break;
            default:
                //TE.log("SetUniform(%s): %d coords specified, maximum 4 allowed", name, columns);
                break;
        }
    }

    public void setUniform(String name, FloatBuffer vec, int columns) {
        switch (columns) {
            case 1:
                addUniform(name, UniformTypes.FLOAT1VEC, vec);
                break;
            case 2:
                addUniform(name, UniformTypes.FLOAT2VEC, vec);
                break;
            case 3:
                addUniform(name, UniformTypes.FLOAT3VEC, vec);
                break;
            case 4:
                addUniform(name, UniformTypes.FLOAT4VEC, vec);
                break;
            default:
                //TE.log("SetUniform(%s): %d coords specified, maximum 4 allowed", name, columns);
                break;
        }
    }

    /**
     * Internal - Creates a uniform for a square floating point matrix, of size
     * 2x2, 3x3 or 4x4
     * @param name of uniform
     * @param vec Floating point matrix data, in row major order
     * @param sz Size of matrix (Number of rows & columns.  2,3 or 4)
     */
    public void setUniformMatrix(String name, FloatBuffer vec, int sz) {
        switch (sz) {
            case 2:
                addUniform(name, UniformTypes.MAT2, vec);
                break;
            case 3:
                addUniform(name, UniformTypes.MAT3, vec);
                break;
            case 4:
                addUniform(name, UniformTypes.MAT4, vec);
                break;
            default:
                //TE.log("SetUniformMatrix(%s): %d incorrect matrix size specified", name, columns);
                break;
        }
    }

    /**
     * Sets a uniform for a square floating point matrix, of type
     * MAT2(2x2), MAT3(3x3) or MAT4(4x4).  Input matrix must be
     * in row major order.
     */
    public void setUniform(String name,float [][] matrix) {
        // requires a square matrix of dimension 2x2,3x3 or 4x4
        int dim = matrix.length;
        if (dim < 2 || dim > 4) {
          TE.log("SetUniform(%s): %d incorrect matrix size specified", name, dim);
          return;
        }

        FloatBuffer buf = Buffers.newDirectFloatBuffer(dim);

        // load matrix into buffer in row major order
        for (int r = 0; r < dim; r++) {
            for (int c = 0; c < dim; c++ ) {
                buf.put(matrix[r][c]);
            }
        }

        // and set the uniform object
        buf.rewind();
        setUniformMatrix(name,buf,dim);
    }

    /**
     * Sets a uniform for a square floating point matrix, of type
     * MAT2(2x2), MAT3(3x3) or MAT4(4x4)
     */
    public void setUniform(String name,Matrix matrix) {
        // requires a square matrix of dimension 2x2,3x3 or 4x4
        int dim = matrix.getRowDimension();
        if (dim < 2 || dim > 4 || dim != matrix.getColumnDimension()) {
            TE.log("SetUniform(%s): %d invalid matrix dimension specified.", name);
            return;
        }

        // allocate buffer to send to OpenGl
        FloatBuffer buf = Buffers.newDirectFloatBuffer(dim);

        // load matrix into buffer in row major order
        for (int r = 0; r < dim; r++) {
            for (int c = 0; c < dim; c++ ) {
                buf.put((float) matrix.get(r,c));
            }
        }

        // and set the uniform object
        buf.rewind();
        setUniformMatrix(name,buf,dim);
    }
}
