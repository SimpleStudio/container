package one.codehz.container

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.support.design.widget.FloatingActionButton
import android.support.design.widget.Snackbar
import android.support.v4.content.ContextCompat
import android.support.v4.content.Loader
import android.support.v7.widget.DefaultItemAnimator
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.support.v7.widget.helper.ItemTouchHelper
import android.widget.EditText
import one.codehz.container.adapters.UserListAdapter
import one.codehz.container.base.BaseActivity
import one.codehz.container.ext.MakeLoaderCallbacks
import one.codehz.container.ext.get
import one.codehz.container.ext.setBackground
import one.codehz.container.ext.vUserManager
import one.codehz.container.models.UserModel

class UserManagerActivity : BaseActivity(R.layout.user_manager_activity) {
    companion object {
        val USER_LIST = 0
        val DELETE_USER = 1
    }

    val contentList by lazy<RecyclerView> { this[R.id.content_list] }
    val fab by lazy<FloatingActionButton> { this[R.id.fab] }
    val linearLayoutManager by lazy { LinearLayoutManager(this) }
    val contentAdapter by lazy {
        UserListAdapter { model ->
            startActivityForResult(Intent(this, UserSettingActivity::class.java).apply {
                putExtra(UserSettingActivity.EXTRA_USER_ID, model.id)
            }, 0)
        }
    }
    val userListLoader by MakeLoaderCallbacks({ this }, { it() }) { contentAdapter.updateModels(vUserManager.users.map(::UserModel)) }
    val deleteUserList = mutableListOf<UserModel>()
    val userDeleteLoader by MakeLoaderCallbacks({ this }, { supportLoaderManager.getLoader<Loader<*>>(USER_LIST).forceLoad() }) {
        deleteUserList.forEach {
            vUserManager.wipeUser(it.id)
            vUserManager.removeUser(it.id)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initViews()

        supportLoaderManager.restartLoader(USER_LIST, null, userListLoader)
    }

    override fun onDestroy() {
        deleteUserList.forEach {
            vUserManager.wipeUser(it.id)
            vUserManager.removeUser(it.id)
        }
        super.onDestroy()
    }

    private fun initViews() {
        with(contentList) {
            adapter = contentAdapter
            layoutManager = linearLayoutManager
            itemAnimator = DefaultItemAnimator()
            setHasFixedSize(true)

            ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.END) {
                override fun getMovementFlags(recyclerView: RecyclerView?, viewHolder: RecyclerView.ViewHolder)
                        = if ((viewHolder as UserListAdapter.ViewHolder).currentModel!!.id != 0) makeMovementFlags(0, ItemTouchHelper.END) else 0

                override fun onMove(recyclerView: RecyclerView?, viewHolder: RecyclerView.ViewHolder?, target: RecyclerView.ViewHolder?) = false

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    viewHolder as UserListAdapter.ViewHolder
                    val currentModel = viewHolder.currentModel!!
                    val deleteAction = contentAdapter.enqueueDelete(currentModel)
                    var undo = false
                    deleteUserList += currentModel

                    Snackbar.make(viewHolder.itemView, R.string.deleted, Snackbar.LENGTH_SHORT)
                            .setBackground(ContextCompat.getColor(this@UserManagerActivity, R.color.colorPrimaryDark))
                            .setAction(R.string.undo) {
                                undo = true
                                deleteAction()
                                deleteUserList -= currentModel
                                supportLoaderManager.restartLoader(USER_LIST, null, userListLoader)
                            }
                            .addCallback(object : Snackbar.Callback() {
                                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                                    if (undo) return
                                    deleteAction()
                                    supportLoaderManager.restartLoader(DELETE_USER, null, userDeleteLoader)
                                }
                            })
                            .show()
                    supportLoaderManager.restartLoader(USER_LIST, null, userListLoader)
                }
            }).attachToRecyclerView(this)
        }
        fab.setOnClickListener {
            AlertDialog.Builder(this)
                    .setTitle(getString(R.string.input_name))
                    .setView(R.layout.input_layout)
                    .setPositiveButton(android.R.string.ok) { dialog, _ ->
                        vUserManager.createUser(((dialog as AlertDialog).findViewById<EditText>(R.id.input)).text.toString(), 0)
                        supportLoaderManager.restartLoader(USER_LIST, null, userListLoader)
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> }
                    .show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        supportLoaderManager.getLoader<Loader<*>>(USER_LIST).forceLoad()
    }
}