package com.discounthunter.android.ui.deals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterAltOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.discounthunter.android.R
import com.discounthunter.android.core.model.DiscountFilter
import com.discounthunter.android.core.model.SortOrder
import com.discounthunter.android.core.model.StoreId

/**
 * The filter and sorting sheet.
 *
 * Edits are held locally and applied on "Show results", so dragging the list while the sheet is
 * half-closed does not happen. Everything here runs against data already in memory — closing this
 * sheet never causes a network request.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(
    state: DealsUiState,
    onDismiss: () -> Unit,
    onToggleStore: (StoreId) -> Unit,
    onDiscountChange: (DiscountFilter) -> Unit,
    onSortChange: (SortOrder) -> Unit,
    onTitleQueryChange: (String) -> Unit,
    onReset: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember(state.titleQuery) { mutableStateOf(state.titleQuery) }
    var discount by remember(state.discountFilter) { mutableStateOf(state.discountFilter) }
    var sort by remember(state.sortOrder) { mutableStateOf(state.sortOrder) }
    var stores by remember(state.enabledStores) { mutableStateOf(state.enabledStores) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    fun apply() {
        stores.forEach { if (it !in state.enabledStores) onToggleStore(it) }
        state.enabledStores.forEach { if (it !in stores) onToggleStore(it) }
        onDiscountChange(discount)
        onSortChange(sort)
        onTitleQueryChange(query)
        keyboard?.hide()
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            SheetHeader(
                title = stringResource(R.string.deals_filter_title),
                onReset = onReset,
                onClose = onDismiss,
            )

            Spacer(Modifier.height(16.dp))

            SectionLabel(stringResource(R.string.deals_filter_stores))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StoreId.MAJOR.forEach { store ->
                    StoreToggleChip(
                        store = store,
                        selected = store in stores,
                        onClick = {
                            stores = if (store in stores) stores - store else stores + store
                        },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            SectionLabel(stringResource(R.string.deals_filter_discount))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DiscountFilter.entries.forEach { option ->
                    FilterChip(
                        selected = option == discount,
                        onClick = { discount = option },
                        label = {
                            Text(
                                text = when (option) {
                                    DiscountFilter.ALL -> stringResource(R.string.discount_all)
                                    DiscountFilter.FREE_ONLY -> stringResource(R.string.discount_free)
                                    DiscountFilter.OVER_75 -> stringResource(R.string.discount_over_75)
                                    DiscountFilter.OVER_50 -> stringResource(R.string.discount_over_50)
                                    DiscountFilter.OVER_25 -> stringResource(R.string.discount_over_25)
                                },
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            SectionLabel(stringResource(R.string.deals_filter_sort))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SortOrder.entries.forEach { option ->
                    FilterChip(
                        selected = option == sort,
                        onClick = { sort = option },
                        label = {
                            Text(
                                text = when (option) {
                                    SortOrder.BIGGEST_DISCOUNT -> stringResource(R.string.sort_biggest_discount)
                                    SortOrder.LOWEST_PRICE -> stringResource(R.string.sort_lowest_price)
                                    SortOrder.TITLE_ASCENDING -> stringResource(R.string.sort_title)
                                },
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            SectionLabel(stringResource(R.string.deals_filter_search))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.deals_filter_search)) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Clear")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                shape = MaterialTheme.shapes.small,
            )

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = ::apply,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(stringResource(R.string.deals_filter_apply))
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SheetHeader(title: String, onReset: () -> Unit, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onReset) {
            Icon(
                imageVector = Icons.Outlined.FilterAltOff,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.deals_filter_reset))
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.search_clear))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

