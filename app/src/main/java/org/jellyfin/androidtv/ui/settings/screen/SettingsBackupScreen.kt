package org.jellyfin.androidtv.ui.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.backup.BackupRestoreActivity
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn

@Composable
fun SettingsBackupScreen() {
	val context = LocalContext.current

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.settings).uppercase()) },
				headingContent = { Text(stringResource(R.string.backup_restore_title)) },
				captionContent = { Text(stringResource(R.string.backup_restore_description)) },
			)
		}

		item {
			ListButton(
				headingContent = { Text(stringResource(R.string.backup_create)) },
				captionContent = { Text(stringResource(R.string.backup_create_description)) },
				onClick = {
					context.startActivity(BackupRestoreActivity.createIntent(context, BackupRestoreActivity.Operation.BACKUP))
				},
				modifier = Modifier.focusKey("backup_create"),
			)
		}

		item {
			ListButton(
				headingContent = { Text(stringResource(R.string.backup_restore_restore)) },
				captionContent = { Text(stringResource(R.string.backup_restore_description_action)) },
				onClick = {
					context.startActivity(BackupRestoreActivity.createIntent(context, BackupRestoreActivity.Operation.RESTORE))
				},
				modifier = Modifier.focusKey("backup_restore"),
			)
		}
	}
}
