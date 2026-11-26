package com.example.ui.grades

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.core.model.Grade
import com.example.ui.components.OnlineInlineErrorBanner
import com.example.ui.components.OnlineStatusStateView
import com.example.ui.theme.DangerRed
import com.example.ui.theme.Dimens
import com.example.ui.theme.PrimaryIndigo
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GradesScreen(
    onNavigateBack: () -> Unit,
    viewModel: GradesViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    BackHandler {
        when {
            uiState.showAddEditDialog -> viewModel.dismissAddEditDialog()
            uiState.gradeToDelete != null -> viewModel.dismissDeleteDialog()
            else -> onNavigateBack()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is GradesEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "إدارة الصفوف الدراسية",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("grades_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "رجوع"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::restoreStageGrades,
                        enabled = !uiState.isSaving,
                        modifier = Modifier.testTag("restore_stage_grades_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "استعادة صفوف المرحلة"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = viewModel::openAddDialog,
                containerColor = PrimaryIndigo,
                contentColor = Color.White,
                icon = {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null)
                },
                text = {
                    Text("إضافة صف", fontWeight = FontWeight.Bold)
                },
                modifier = Modifier.testTag("add_grade_fab")
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier.testTag("grades_screen")
    ) { innerPadding ->
        if (uiState.isLoading && uiState.grades.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = PrimaryIndigo)
            }
        } else if (uiState.errorMessage != null && uiState.grades.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                OnlineStatusStateView(
                    isOffline = uiState.isOfflineError,
                    message = uiState.errorMessage!!,
                    onRetry = { viewModel.refresh() }
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(
                    start = Dimens.Spacing20,
                    end = Dimens.Spacing20,
                    top = Dimens.Spacing12,
                    bottom = 96.dp
                ),
                verticalArrangement = Arrangement.spacedBy(Dimens.Spacing12)
            ) {
                if (uiState.errorMessage != null) {
                    item {
                        OnlineInlineErrorBanner(
                            isOffline = uiState.isOfflineError,
                            message = uiState.errorMessage!!,
                            onRetry = { viewModel.refresh() }
                        )
                    }
                }
                // Summary Banner
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Dimens.Radius20),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Dimens.Spacing16),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(PrimaryIndigo.copy(alpha = 0.14f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.School,
                                        contentDescription = null,
                                        tint = PrimaryIndigo
                                    )
                                }
                                Spacer(modifier = Modifier.width(Dimens.Spacing12))
                                Column {
                                    Text(
                                        text = "المرحلة: ${uiState.educationalStage}",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "عدد الصفوف المسجلة: ${uiState.grades.size}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            OutlinedButton(
                                onClick = viewModel::restoreStageGrades,
                                enabled = !uiState.isSaving,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("مزامنة المرحلة", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }

                if (uiState.grades.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(Dimens.Radius20),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(Dimens.Spacing24),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(Dimens.Spacing10)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.MenuBook,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(40.dp)
                                )
                                Text(
                                    text = "لا توجد صفوف دراسية مسجلة حالياً",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "اضغط على \"إضافة صف\" أو \"مزامنة المرحلة\" لإضافة الصفوف الافتراضية.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(uiState.grades, key = { it.id }) { grade ->
                        GradeItemCard(
                            grade = grade,
                            onEdit = { viewModel.openEditDialog(grade) },
                            onDelete = { viewModel.requestDeleteGrade(grade) }
                        )
                    }
                }
            }
        }
    }

    if (uiState.showAddEditDialog) {
        val isEdit = uiState.gradeToEdit != null
        AlertDialog(
            onDismissRequest = viewModel::dismissAddEditDialog,
            title = {
                Text(
                    text = if (isEdit) "تعديل الصف الدراسي" else "إضافة صف دراسي جديد",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing12)) {
                    OutlinedTextField(
                        value = uiState.gradeNameInput,
                        onValueChange = viewModel::onGradeNameChange,
                        label = { Text("اسم الصف الدراسي") },
                        placeholder = { Text("مثال: الأول الإعدادي") },
                        singleLine = true,
                        isError = uiState.inputError != null,
                        supportingText = uiState.inputError?.let { { Text(it) } },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("grade_name_input")
                    )

                    OutlinedTextField(
                        value = uiState.gradeOrderInput,
                        onValueChange = viewModel::onGradeOrderChange,
                        label = { Text("ترتيب العرض") },
                        placeholder = { Text("1") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("grade_order_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = viewModel::saveGrade,
                    enabled = !uiState.isSaving,
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo),
                    modifier = Modifier.testTag("save_grade_button")
                ) {
                    Text(if (isEdit) "حفظ التعديل" else "إضافة")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = viewModel::dismissAddEditDialog,
                    enabled = !uiState.isSaving
                ) {
                    Text("إلغاء")
                }
            }
        )
    }

    uiState.gradeToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteDialog,
            title = {
                Text(
                    text = "حذف الصف الدراسي",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing8)) {
                    Text(
                        text = "هل أنت متأكد من حذف صف \"${target.name}\"؟",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (!uiState.deleteError.isNullOrBlank()) {
                        Text(
                            text = uiState.deleteError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = DangerRed
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = viewModel::confirmDeleteGrade,
                    enabled = !uiState.isSaving,
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                    modifier = Modifier.testTag("confirm_delete_grade_button")
                ) {
                    Text("حذف")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = viewModel::dismissDeleteDialog,
                    enabled = !uiState.isSaving
                ) {
                    Text("إلغاء")
                }
            }
        )
    }
}

@Composable
private fun GradeItemCard(
    grade: Grade,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("grade_card_${grade.id}"),
        shape = RoundedCornerShape(Dimens.Radius16),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.Spacing16, vertical = Dimens.Spacing12),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(PrimaryIndigo.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = grade.displayOrder.toString(),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = PrimaryIndigo
                )
            }

            Spacer(modifier = Modifier.width(Dimens.Spacing12))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = grade.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                ) {
                    Text(
                        text = "${grade.studentCount} طالب",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            IconButton(
                onClick = onEdit,
                modifier = Modifier.testTag("edit_grade_${grade.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "تعديل الصف",
                    tint = PrimaryIndigo
                )
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.testTag("delete_grade_${grade.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "حذف الصف",
                    tint = DangerRed
                )
            }
        }
    }
}
