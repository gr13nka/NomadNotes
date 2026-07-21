package com.nomadnotes.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.nomadnotes.R
import com.nomadnotes.app.storage.NotebookStorage
import com.nomadnotes.app.storage.notebooksRoot
import com.nomadnotes.app.ui.ConfirmDialog
import com.nomadnotes.app.ui.EinkBlack
import com.nomadnotes.app.ui.EinkButton
import com.nomadnotes.app.ui.EinkDialogCard
import com.nomadnotes.app.ui.EinkGray
import com.nomadnotes.app.ui.EinkTextField
import com.nomadnotes.app.ui.EinkTheme
import com.nomadnotes.app.ui.EinkWhite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The launcher screen: a list of notebooks with create, rename, and delete, from which the user
 * opens one into [EditorActivity].
 *
 * As the app's entry point, this is also where All-Files access is requested once per process (the
 * editor no longer asks). Declining is fine — [notebooksRoot] falls back to app-private storage —
 * so the request never blocks the list from working.
 */
class NotebookListActivity : ComponentActivity() {

    private lateinit var storage: NotebookStorage

    private var uiNotebooks by mutableStateOf<List<String>>(emptyList())

    // At most one dialog is shown at a time; these flags/targets pick which.
    private var uiNewDialog by mutableStateOf(false)
    private var uiRenameTarget by mutableStateOf<String?>(null)
    private var uiDeleteTarget by mutableStateOf<String?>(null)
    private var uiMenuTarget by mutableStateOf<String?>(null)
    private var uiNameInput by mutableStateOf("")
    private var uiNameError by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = notebooksRoot(this)
        Log.i(TAG, "Storage root: ${root.path} (allFilesAccess=${Environment.isExternalStorageManager()})")
        storage = NotebookStorage(root)
        requestAllFilesAccessOnce()
        setContent { EinkTheme { NotebookListScreen() } }
    }

    override fun onResume() {
        super.onResume()
        // Refresh on every return so a notebook created, renamed, or deleted here — or edits made in
        // the editor — are reflected when the user comes back to the list.
        refreshNotebooks()
    }

    private fun refreshNotebooks() {
        lifecycleScope.launch {
            uiNotebooks = withContext(Dispatchers.IO) {
                runCatching { storage.listNotebooks().map { it.name } }.getOrDefault(emptyList())
            }
        }
    }

    private fun openNotebook(name: String) {
        startActivity(
            Intent(this, EditorActivity::class.java)
                .putExtra(EditorActivity.EXTRA_NOTEBOOK_NAME, name),
        )
    }

    private fun openNewDialog() {
        uiNameInput = ""
        uiNameError = null
        uiNewDialog = true
    }

    private fun closeNameDialog() {
        uiNewDialog = false
        uiRenameTarget = null
        uiNameInput = ""
        uiNameError = null
    }

    private fun submitNewNotebook() {
        val name = uiNameInput.trim()
        if (uiNotebooks.contains(name)) {
            uiNameError = getString(R.string.error_name_taken)
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { storage.createNotebook(name) } }
            result
                .onSuccess {
                    closeNameDialog()
                    refreshNotebooks()
                }
                .onFailure { e -> uiNameError = nameErrorMessage(e) }
        }
    }

    private fun submitRename(old: String) {
        val name = uiNameInput.trim()
        if (name == old) {
            closeNameDialog()
            return
        }
        if (uiNotebooks.contains(name)) {
            uiNameError = getString(R.string.error_name_taken)
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { storage.renameNotebook(old, name) } }
            result
                .onSuccess {
                    closeNameDialog()
                    refreshNotebooks()
                }
                .onFailure { e -> uiNameError = nameErrorMessage(e) }
        }
    }

    private fun confirmDelete(name: String) {
        uiDeleteTarget = null
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { storage.deleteNotebook(name) } }
                .onFailure { e -> Log.e(TAG, "Could not delete notebook $name", e) }
            refreshNotebooks()
        }
    }

    /** An invalid name is a caller error ([IllegalArgumentException]); anything else is a disk fault. */
    private fun nameErrorMessage(e: Throwable): String = when (e) {
        is IllegalArgumentException -> getString(R.string.error_name_invalid)
        else -> getString(R.string.error_generic)
    }

    // --- Compose UI ------------------------------------------------------------------------

    @Composable
    private fun NotebookListScreen() {
        Box(Modifier.fillMaxSize().background(EinkWhite)) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.notebooks_title), color = EinkBlack, fontSize = 24.sp)
                    EinkButton(stringResource(R.string.notebook_new)) { openNewDialog() }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(EinkBlack))

                if (uiNotebooks.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.notebooks_empty), color = EinkGray, fontSize = 16.sp)
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                    ) {
                        for (name in uiNotebooks) {
                            NotebookRow(name)
                            Box(Modifier.fillMaxWidth().height(1.dp).background(EinkGray))
                        }
                    }
                }
            }
            NotebookDialogs()
        }
    }

    @Composable
    private fun NotebookRow(name: String) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clickable { openNotebook(name) }
                    .padding(vertical = 16.dp),
            ) {
                Text(name, color = EinkBlack, fontSize = 17.sp, maxLines = 1)
            }
            EinkButton("⋮") { uiMenuTarget = name }
        }
    }

    @Composable
    private fun NotebookDialogs() {
        if (uiNewDialog) {
            NameDialog(
                title = stringResource(R.string.new_notebook_title),
                confirmLabel = stringResource(R.string.action_create),
                onConfirm = { submitNewNotebook() },
            )
        }
        uiRenameTarget?.let { target ->
            NameDialog(
                title = stringResource(R.string.rename_notebook_title),
                confirmLabel = stringResource(R.string.action_rename),
                onConfirm = { submitRename(target) },
            )
        }
        uiDeleteTarget?.let { target ->
            ConfirmDialog(
                title = stringResource(R.string.delete_notebook_title),
                message = stringResource(R.string.delete_notebook_message, target),
                confirmLabel = stringResource(R.string.action_delete),
                cancelLabel = stringResource(R.string.action_cancel),
                onConfirm = { confirmDelete(target) },
                onDismiss = { uiDeleteTarget = null },
            )
        }
        uiMenuTarget?.let { target -> ItemMenu(target) }
    }

    @Composable
    private fun NameDialog(title: String, confirmLabel: String, onConfirm: () -> Unit) {
        EinkDialogCard(onDismiss = { closeNameDialog() }) {
            Text(title, color = EinkBlack, fontSize = 18.sp)
            EinkTextField(
                value = uiNameInput,
                onValueChange = {
                    uiNameInput = it
                    uiNameError = null
                },
                placeholder = stringResource(R.string.notebook_name_hint),
                modifier = Modifier.fillMaxWidth(),
            )
            uiNameError?.let { Text(it, color = EinkBlack, fontSize = 13.sp) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                EinkButton(stringResource(R.string.action_cancel)) { closeNameDialog() }
                EinkButton(confirmLabel, enabled = uiNameInput.isNotBlank()) { onConfirm() }
            }
        }
    }

    @Composable
    private fun ItemMenu(name: String) {
        EinkDialogCard(onDismiss = { uiMenuTarget = null }) {
            Text(name, color = EinkBlack, fontSize = 18.sp, maxLines = 1)
            EinkButton(stringResource(R.string.item_rename), modifier = Modifier.fillMaxWidth()) {
                uiMenuTarget = null
                uiNameInput = name
                uiNameError = null
                uiRenameTarget = name
            }
            EinkButton(stringResource(R.string.item_delete), modifier = Modifier.fillMaxWidth()) {
                uiMenuTarget = null
                uiDeleteTarget = name
            }
            EinkButton(stringResource(R.string.action_cancel), modifier = Modifier.fillMaxWidth()) {
                uiMenuTarget = null
            }
        }
    }

    /**
     * Prompts for All-Files access at most once per process (a declined prompt is not re-shown, so
     * there is no nag loop). The app works without it: [notebooksRoot] falls back to app-private
     * storage, only the storage location differs.
     */
    private fun requestAllFilesAccessOnce() {
        if (Environment.isExternalStorageManager() || allFilesAccessRequested) return
        allFilesAccessRequested = true
        val perApp = Intent(
            Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", packageName, null),
        )
        try {
            startActivity(perApp)
        } catch (e: ActivityNotFoundException) {
            // A few devices lack the per-app screen; fall back to the global All-Files list.
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (e2: ActivityNotFoundException) {
                Log.w(TAG, "No All-Files access settings screen; staying on the fallback root", e2)
            }
        }
    }

    private companion object {
        const val TAG = "NotebookListActivity"

        /** Whether this process has already shown the All-Files access prompt; set once, never reset. */
        var allFilesAccessRequested = false
    }
}
