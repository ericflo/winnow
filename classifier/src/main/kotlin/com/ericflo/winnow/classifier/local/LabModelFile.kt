package com.ericflo.winnow.classifier.local

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * A Lab model as a file, by its recipe: a linear model or a network as each writes itself, a
 * blend as its members one after another. What a recipe says about reading texts (pieces of
 * words) isn't in the file: the recipe kept with it says so, and [read] wraps the model to match.
 */
object LabModelFile {
    private const val BLEND_MAGIC = 0x57424c44 // "WBLD"
    private const val BLEND_FORMAT = 1
    private const val LEAN_MAGIC = 0x574c454e // "WLEN"

    /** Writes [model] (trained by [recipe]) at [temperature]; false for the personal layer, which keeps no file. */
    fun write(recipe: Recipe, model: Predictor, temperature: Float, out: OutputStream): Boolean {
        // Leanings (kept in the recipe) at the calibration, the model under them at its own odds.
        if (recipe.classBias.isNotEmpty() && model is BiasedPredictor) {
            val o = DataOutputStream(out)
            o.writeInt(LEAN_MAGIC)
            o.writeFloat(temperature)
            o.flush()
            return write(recipe.copy(classBias = emptyList()), model.inner, 1f, out)
        }
        when (recipe.kind) {
            RecipeKind.PERSONAL -> return false
            RecipeKind.LINEAR -> when (val linear = unwrap(model)) {
                is MeaningPredictor -> MeaningPredictor(linear.model, linear.meaning, linear.dim, temperature).write(out)
                else -> ((linear as LinearPredictor).model.withTemperature(temperature)).write(out)
            }
            RecipeKind.NEURAL -> (unwrap(model) as NeuralModel).also { it.temperature = temperature }.write(out)
            RecipeKind.BLEND -> {
                val blend = model as BlendPredictor
                val o = DataOutputStream(out)
                o.writeInt(BLEND_MAGIC)
                o.writeInt(BLEND_FORMAT)
                o.writeFloat(temperature)
                o.writeInt(blend.members.size)
                // Each member at its own odds: the blend is calibrated as a whole.
                recipe.members.zip(blend.members).forEach { (r, m) ->
                    val bytes = ByteArrayOutputStream().also { write(r, m, 1f, it) }.toByteArray()
                    o.writeInt(bytes.size)
                    o.write(bytes)
                }
                o.flush()
            }
        }
        return true
    }

    /** The model [recipe] trained, from what [write] wrote. */
    fun read(recipe: Recipe, input: InputStream): Predictor? {
        if (recipe.classBias.isNotEmpty()) {
            val d = DataInputStream(input)
            require(d.readInt() == LEAN_MAGIC) { "Not a model with leanings" }
            val temperature = d.readFloat()
            val inner = read(recipe.copy(classBias = emptyList()), d) ?: return null
            return BiasedPredictor(inner, recipe.leaningsIn(inner.classes), temperature)
        }
        val model: Predictor = when (recipe.kind) {
            RecipeKind.PERSONAL -> return null
            RecipeKind.LINEAR -> if (recipe.meaning) MeaningPredictor.read(input) else LinearPredictor(LocalModel.read(input))
            RecipeKind.NEURAL -> NeuralModel.read(input)
            RecipeKind.BLEND -> {
                val d = DataInputStream(input)
                require(d.readInt() == BLEND_MAGIC) { "Not a Winnow blend" }
                require(d.readInt() == BLEND_FORMAT) { "Unsupported blend format" }
                val temperature = d.readFloat()
                val n = d.readInt()
                require(n == recipe.members.size) { "The blend's file and its recipe don't match" }
                val members = recipe.members.map { r ->
                    val bytes = ByteArray(d.readInt()).also(d::readFully)
                    read(r, ByteArrayInputStream(bytes)) ?: error("A blend member couldn't be read")
                }
                return BlendPredictor(members, recipe.memberWeights.ifEmpty { List(n) { 1.0 } }, temperature)
            }
        }
        val pieced = if (recipe.pieces) PiecesPredictor(model) else model
        val grouped = if (recipe.clusters) ClustersPredictor(pieced) else pieced
        val read = if (recipe.crosses) CrossesPredictor(grouped) else grouped
        val shaped = if (recipe.shapes) ShapesPredictor(read) else read
        return if (recipe.context) ContextPredictor(shaped) else shaped
    }

    /** How many numbers [model] holds. */
    fun parameters(model: Predictor): Long = when (model) {
        is NeuralModel -> model.parameters
        is LinearPredictor -> model.model.buckets.toLong() * model.model.classes.size + model.adjustments.size.toLong() * model.model.classes.size
        is MeaningPredictor -> model.model.buckets.toLong() * model.model.classes.size + model.meaning.size
        is PiecesPredictor -> parameters(model.inner)
        is ClustersPredictor -> parameters(model.inner)
        is ContextPredictor -> parameters(model.inner)
        is CrossesPredictor -> parameters(model.inner)
        is ShapesPredictor -> parameters(model.inner)
        is BiasedPredictor -> parameters(model.inner) + model.bias.size
        is BlendPredictor -> model.members.sumOf(::parameters)
        else -> 0
    }

    /** Sets a trained model's calibration, wherever it keeps it. */
    fun calibrate(model: Predictor, temperature: Float): Predictor = when (model) {
        is NeuralModel -> model.also { it.temperature = temperature }
        is LinearPredictor -> if (model.adjustments.size == 0) LinearPredictor(model.model.withTemperature(temperature)) else model
        is MeaningPredictor -> model.also { it.temperature = temperature }
        is PiecesPredictor -> PiecesPredictor(calibrate(model.inner, temperature))
        is ClustersPredictor -> ClustersPredictor(calibrate(model.inner, temperature))
        is ContextPredictor -> ContextPredictor(calibrate(model.inner, temperature))
        is CrossesPredictor -> CrossesPredictor(calibrate(model.inner, temperature))
        is ShapesPredictor -> ShapesPredictor(calibrate(model.inner, temperature))
        // Calibrated on top: the model under it stays at its own odds.
        is BiasedPredictor -> model.also { it.temperature = temperature }
        is BlendPredictor -> model.also { it.temperature = temperature }
        else -> model
    }

    private fun unwrap(model: Predictor): Predictor = when (model) {
        is PiecesPredictor -> unwrap(model.inner)
        is ClustersPredictor -> unwrap(model.inner)
        is ContextPredictor -> unwrap(model.inner)
        is CrossesPredictor -> unwrap(model.inner)
        is ShapesPredictor -> unwrap(model.inner)
        else -> model
    }
}
