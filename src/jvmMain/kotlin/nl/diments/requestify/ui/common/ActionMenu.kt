package nl.diments.requestify.ui.common

import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector

/** One entry of an [ActionMenu]. */
class MenuAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * An Expressive menu: one group of connected items (rounded outside, tighter between them), each with its icon.
 * Place it inside the box of the button that opens it. Picking an item closes the menu, then runs its action.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ActionMenu(expanded: Boolean, onDismiss: () -> Unit, actions: List<MenuAction>) {
    DropdownMenuPopup(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
            actions.forEachIndexed { index, action ->
                DropdownMenuItem(
                    text = { Text(action.label) },
                    onClick = {
                        onDismiss()
                        action.onClick()
                    },
                    shape = itemShape(index, actions.size),
                    leadingIcon = { Icon(action.icon, contentDescription = null) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun itemShape(index: Int, count: Int): Shape = when {
    count == 1 -> MenuDefaults.standaloneItemShape
    index == 0 -> MenuDefaults.leadingItemShape
    index == count - 1 -> MenuDefaults.trailingItemShape
    else -> MenuDefaults.middleItemShape
}
