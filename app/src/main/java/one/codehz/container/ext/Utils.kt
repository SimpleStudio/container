package one.codehz.container.ext

import android.app.Activity
import android.app.ActivityManager
import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.os.AsyncTask
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.preference.PreferenceManager
import android.support.design.widget.Snackbar
import android.support.v4.app.FragmentManager
import android.support.v4.app.FragmentTransaction
import android.support.v4.app.LoaderManager
import android.support.v4.content.AsyncTaskLoader
import android.support.v4.content.FileProvider
import android.support.v7.util.DiffUtil
import android.support.v7.widget.RecyclerView
import android.view.View
import com.lody.virtual.client.VClientImpl
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.client.ipc.VActivityManager
import com.lody.virtual.client.ipc.VNotificationManager
import com.lody.virtual.client.ipc.VPackageManager
import com.lody.virtual.os.VUserManager
import one.codehz.container.base.SameAsAble
import one.codehz.container.provider.RunningWidgetProvier
import java.io.File
import kotlin.coroutines.experimental.buildSequence
import kotlin.reflect.KProperty

typealias ViewBinding<T, R> = Pair<(View, R) -> Unit, (T) -> R>

infix fun <T, R> T.then(fn: T.() -> R) = run(fn)

fun getUriFromFile(file: File) = FileProvider.getUriForFile(virtualCore.context, "one.codehz.container.fileprovider", file)!!

@Suppress("EXPERIMENTAL_FEATURE_WARNING")
fun Cursor.asSequence() = buildSequence { while (moveToNext()) yield(this@asSequence) }

@Suppress("UNCHECKED_CAST")
operator fun <T> View.get(id: Int) = this.findViewById<View>(id) as T

@Suppress("UNCHECKED_CAST")
operator fun <T> Dialog.get(id: Int) = this.findViewById<View>(id) as T

@Suppress("UNCHECKED_CAST")
operator fun <T> Activity.get(id: Int) = this.findViewById<View>(id) as T

infix fun View.pair(name: String) = android.util.Pair(this, name)

val virtualCore: VirtualCore by lazy { VirtualCore.get() }
val vClientImpl: VClientImpl by lazy { VClientImpl.get() }
val vActivityManager: VActivityManager by lazy { VActivityManager.get() }
val vPackageManager: VPackageManager by lazy { VPackageManager.get() }
val vUserManager: VUserManager by lazy { VUserManager.get() }
val vNotificationManager: VNotificationManager by lazy { VNotificationManager.get() }
val sharedPreferences: SharedPreferences by lazy { PreferenceManager.getDefaultSharedPreferences(virtualCore.context) }
val clipboardManager: ClipboardManager by lazy { virtualCore.context.systemService<ClipboardManager>(Context.CLIPBOARD_SERVICE) }
val activityManager: ActivityManager by lazy { virtualCore.context.systemService<ActivityManager>(Context.ACTIVITY_SERVICE) }

fun FragmentManager.transaction(fn: FragmentTransaction.() -> Unit) = beginTransaction().apply(fn).commit()

fun VirtualCore.killAppEx(pkgName: String, uid: Int) {
    killApp(pkgName, uid)
    activityManager.appTasks
            .filter { it.taskInfo.baseIntent.component.className.startsWith("com.lody.virtual.client.stub.StubActivity$") }
            .filter { it.taskInfo.baseIntent.type.startsWith(pkgName + "/") }
            .forEach(ActivityManager.AppTask::finishAndRemoveTask)
    RunningWidgetProvier.forceUpdate(context)
    vNotificationManager.cancelAllNotification(pkgName, uid)
}

fun VirtualCore.killAllAppsEx() {
    synchronized(this) {
        allApps.map { it.packageName }.forEach { pkgName ->
            vUserManager.users
                    .map { it.id }
                    .filter { uid -> virtualCore.isAppRunning(pkgName, uid) }
                    .onEach { uid -> vNotificationManager.cancelAllNotification(pkgName, uid) }
        }
        virtualCore.killAllApps()
        activityManager.appTasks
                .filter { it.taskInfo.baseIntent.component.className.startsWith("com.lody.virtual.client.stub.StubActivity$") }
                .forEach(ActivityManager.AppTask::finishAndRemoveTask)
        RunningWidgetProvier.forceUpdate(context)
    }

}

infix fun <R, P> ((P) -> R).bind(p: P) = { this.invoke(p) }

fun <T> makeAsyncTaskLoader(context: Context, task: (Context) -> T) = object : AsyncTaskLoader<T>(context) {
    override fun onStartLoading() = forceLoad()
    override fun onStopLoading() {
        cancelLoad()
    }

    override fun loadInBackground() = task(context)
}

class MakeLoaderCallbacks<T>(val contextGetter: () -> Context, val finishedFn: (T) -> Unit, val task: (Context) -> T) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>) = object : LoaderManager.LoaderCallbacks<T> {
        override fun onCreateLoader(id: Int, args: Bundle?) = makeAsyncTaskLoader(contextGetter(), task)
        override fun onLoadFinished(loader: android.support.v4.content.Loader<T>?, data: T) = finishedFn(data)
        override fun onLoaderReset(loader: android.support.v4.content.Loader<T>?) = Unit
    }
}

fun <T : SameAsAble<T>> Pair<List<T>, List<T>>.diffCallback(): DiffUtil.Callback = object : DiffUtil.Callback() {
    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) = first[oldItemPosition] sameAs second[newItemPosition]
    override fun getOldListSize() = first.size
    override fun getNewListSize() = second.size
    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) = first[oldItemPosition] == second[newItemPosition]
    override fun getChangePayload(oldItemPosition: Int, newItemPosition: Int) = first[oldItemPosition].getPayloads(second[newItemPosition])
}

infix fun <T : SameAsAble<T>> MutableList<T>.updateFrom(target: List<T>): (RecyclerView.Adapter<*>) -> Unit {
    val diffCb = (this to target).diffCallback()
    val diffResult = DiffUtil.calculateDiff(diffCb, true)
    return {
        this.clear()
        this.addAll(target)
        diffResult.dispatchUpdatesTo(it)
    }
}

data class AsyncTaskContext(private val publish: (() -> Unit) -> Unit) {
    fun ui(thing: () -> Unit) = publish(thing)
}

class AsyncTaskProxy<T, R>(val backgroundFn: AsyncTaskContext.(Int, T) -> R, val finishedFn: (Int, R) -> Unit) : AsyncTask<T, () -> Unit, List<R>>() {
    override fun onProgressUpdate(vararg values: () -> Unit) = values.forEach { it() }
    override fun doInBackground(vararg params: T) = params.mapIndexed { index, t -> backgroundFn.invoke(AsyncTaskContext { publishProgress(it) }, index, t) }
    override fun onPostExecute(result: List<R>) = result.forEachIndexed(finishedFn)
}

class AsyncTaskBuilder<T, R>(val context: Context, val mapFn: AsyncTaskContext.(Int, T) -> R) {
    fun then(finishedFn: (R) -> Unit) = AsyncTaskProxy(mapFn) { _, input -> finishedFn(input) }
}

fun <T, R> Context.runAsync(mapFn: AsyncTaskContext.(T) -> R) = AsyncTaskBuilder<T, R>(this) { _, input -> mapFn(input) }

inline fun <reified T> Context.systemService(name: String) = getSystemService(name) as T

object staticName {
    operator fun getValue(thisRef: Any?, property: KProperty<*>) = property.name
}

fun Snackbar.setBackground(color: Int) = apply { view.setBackgroundColor(color) }