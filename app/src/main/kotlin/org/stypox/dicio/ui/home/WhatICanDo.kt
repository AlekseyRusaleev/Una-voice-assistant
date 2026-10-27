package org.stypox.dicio.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessAlarm
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.eval.SkillHandler
import org.stypox.dicio.ui.theme.AppTheme
import org.stypox.dicio.ui.util.SkillInfoPreviews

/** A command that Una handles itself, outside of the sentence-based skills. */
private data class Ability(
    val icon: ImageVector,
    val title: String,
    val example: String,
)

private val unaAbilities = listOf(
    Ability(Icons.Default.Edit, "Заметки", "Запиши в заметки купить хлеб"),
    Ability(Icons.Default.Alarm, "Напоминания", "Напомни в 18:30 позвонить маме"),
    Ability(Icons.Default.AccessAlarm, "Будильник и таймер", "Будильник на 7"),
    Ability(Icons.Default.VolumeUp, "Громкость", "Громкость на 7"),
    Ability(Icons.Default.Notifications, "Чтение сообщений", "Что мне писали?"),
    Ability(Icons.Default.Send, "Ответ на сообщение", "Ответь Васе: буду через час"),
    Ability(Icons.Default.PlayArrow, "YouTube", "Включи на Ютубе котиков"),
    Ability(Icons.Default.MusicNote, "Яндекс Музыка", "Включи мою волну"),
    Ability(Icons.Default.Apps, "Открыть приложение", "Открой Телеграм"),
    Ability(Icons.Default.BatteryFull, "Телефон", "Заряд? Беззвучный режим"),
)

@Composable
fun WhatICanDo(skills: List<SkillInfo>) {
    MessageCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            text = stringResource(R.string.here_is_what_i_can_do),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        )

        if (skills.isEmpty()) {
            NoEnabledSkills()
        } else {
            for (skill in skills) {
                SkillRow(
                    skill = skill,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

        Text(
            text = "Свои команды Una",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        for (ability in unaAbilities) {
            AbilityRow(
                ability = ability,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun AbilityRow(ability: Ability, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = ability.icon,
            contentDescription = null,
            modifier = Modifier.size(36.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = ability.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = ability.example,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Preview
@Composable
private fun SkillRow(
    @PreviewParameter(SkillInfoPreviews::class) skill: SkillInfo,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = skill.icon(),
            contentDescription = null,
            modifier = Modifier.size(36.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = skill.name(LocalContext.current),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = skill.sentenceExample(LocalContext.current),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NoEnabledSkills() {
    Text(
        text = stringResource(R.string.all_skills_disabled_title),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 4.dp),
    )
    Text(
        text = stringResource(R.string.all_skills_disabled_description),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
    )
}

@Preview
@Composable
private fun WhatICanDoPreview() {
    AppTheme {
        WhatICanDo(skills = SkillInfoPreviews().values.toList())
    }
}

// this preview is useful to take screenshots for presentations
@Preview
@Composable
private fun WhatICanDoAllPreview() {
    AppTheme {
        WhatICanDo(skills = SkillHandler.newForPreviews(LocalContext.current).allSkillInfoList)
    }
}

@Preview
@Composable
private fun NoEnabledSkillsPreview() {
    AppTheme {
        WhatICanDo(skills = listOf())
    }
}

// this preview is useful to take screenshots for presentations
@Preview(device = "spec:width=2400px,height=2340px,dpi=440")
@Composable
private fun SkillChipsPreview() {
    AppTheme {
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            val skills = SkillHandler.newForPreviews(LocalContext.current).allSkillInfoList
            for (skill in skills) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = skill.icon(),
                            contentDescription = null,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = skill.name(LocalContext.current),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// this preview is useful to take screenshots for presentations
@Preview(device = "spec:width=500px,height=2340px,dpi=440")
@Composable
private fun SkillIconsPreview() {
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(
        horizontalArrangement = Arrangement.Center
    ) {
        val skills = SkillHandler.newForPreviews(LocalContext.current).allSkillInfoList
        for (skill in skills) {
            Icon(
                painter = skill.icon(),
                contentDescription = null,
                tint = Color(0xFF6D9861),
                modifier = Modifier.size(36.dp)
            )
        }
    }
}
