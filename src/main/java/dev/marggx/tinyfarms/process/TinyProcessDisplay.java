package dev.marggx.tinyfarms.process;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.EnumCodec;
import com.hypixel.hytale.codec.validation.Validators;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** How the machine shows the input of a process inside its transparent block. */
public class TinyProcessDisplay {
    public enum Type { Block, Item }

    public static final BuilderCodec<TinyProcessDisplay> CODEC = BuilderCodec.builder(TinyProcessDisplay.class, TinyProcessDisplay::new)
        .append(new KeyedCodec<>("Type", new EnumCodec<>(Type.class), true), (d, v) -> d.type = v, d -> d.type)
            .addValidator(Validators.nonNull())
            .add()
        .append(new KeyedCodec<>("Block", Codec.STRING), (d, v) -> d.block = v, d -> d.block).add()
        .append(new KeyedCodec<>("State", Codec.STRING), (d, v) -> d.state = v, d -> d.state).add()
        .append(new KeyedCodec<>("Item", Codec.STRING), (d, v) -> d.item = v, d -> d.item).add()
        .append(new KeyedCodec<>("ScaleMin", Codec.FLOAT), (d, v) -> d.scaleMin = v, d -> d.scaleMin)
            .addValidator(Validators.greaterThan(0f))
            .add()
        .append(new KeyedCodec<>("ScaleMax", Codec.FLOAT), (d, v) -> d.scaleMax = v, d -> d.scaleMax)
            .addValidator(Validators.greaterThan(0f))
            .add()
        .append(new KeyedCodec<>("GrowWithProgress", Codec.BOOLEAN), (d, v) -> d.growWithProgress = v, d -> d.growWithProgress).add()
        .append(new KeyedCodec<>("Offset", Codec.DOUBLE_ARRAY), (d, v) -> d.offset = v, d -> d.offset)
            .add()
        .append(new KeyedCodec<>("Rotation", Codec.DOUBLE_ARRAY), (d, v) -> d.rotation = v, d -> d.rotation)
            .add()
        .validator((d, results) -> {
            if (d.type == Type.Block && d.block == null) results.fail("Display of Type Block needs a Block");
            if (d.type == Type.Item && d.item == null) results.fail("Display of Type Item needs an Item");
            if (d.offset == null || d.offset.length != 3) results.fail("Offset needs exactly 3 values");
            if (d.rotation == null || d.rotation.length != 3) results.fail("Rotation needs exactly 3 values");
            if (d.scaleMin > d.scaleMax) results.fail("ScaleMin must not be greater than ScaleMax");
        })
        .build();

    private Type type = Type.Item;
    private String block;
    private String state;
    private String item;
    private float scaleMin = 0.35f;
    private float scaleMax = 1.0f;
    private boolean growWithProgress = true;
    private double[] offset = {0, 0.1, 0};
    private double[] rotation = {0, 0, 0};

    public Type getType() {
        return type;
    }

    @Nullable
    public String getBlock() {
        return block;
    }

    @Nullable
    public String getState() {
        return state;
    }

    @Nullable
    public String getItem() {
        return item;
    }

    public float getScaleMin() {
        return scaleMin;
    }

    public float getScaleMax() {
        return scaleMax;
    }

    public boolean isGrowWithProgress() {
        return growWithProgress;
    }

    /** x, y, z relative to the block centre. */
    @Nonnull
    public double[] getOffset() {
        return offset;
    }

    /** yaw, pitch, roll in degrees. */
    @Nonnull
    public double[] getRotation() {
        return rotation;
    }
}
