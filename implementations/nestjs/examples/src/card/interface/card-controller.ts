import {
  BadRequestException, Body, Controller, Get,
  Logger, NotFoundException, Param, Post, Req, UseGuards
} from '@nestjs/common'
import { ApiBearerAuth, ApiCreatedResponse, ApiOkResponse, ApiOperation, ApiTags } from '@nestjs/swagger'
import { CommandBus, QueryBus } from '@nestjs/cqrs'
import { Request } from 'express'

import { generateErrorResponse } from '@/common/generate-error-response'
import { IssueCardCommand } from '@/card/application/command/issue-card-command'
import { Card } from '@/card/domain/card'
import { GetCardQuery } from '@/card/application/query/get-card-query'
import { GetCardResult } from '@/card/application/query/card-result'
import { IssueCardRequestBody } from '@/card/interface/dto/issue-card-request-body'
import { IssueCardResponseBody } from '@/card/interface/dto/issue-card-response-body'
import { GetCardRequestParam } from '@/card/interface/dto/get-card-request-param'
import { GetCardResponseBody } from '@/card/interface/dto/get-card-response-body'
import { CardErrorCode as ErrorCode } from '@/card/card-error-code'
import { CardErrorMessage } from '@/card/card-error-message'
import { AuthGuard } from '@/auth/auth.guard'

type AuthenticatedRequest = Request & { user: { userId: string } }

@Controller()
@ApiTags('Card')
@ApiBearerAuth('token')
@UseGuards(AuthGuard)
export class CardController {
  private readonly logger = new Logger(CardController.name)

  constructor(
    private readonly commandBus: CommandBus,
    private readonly queryBus: QueryBus
  ) {}

  @Post('/cards')
  @ApiOperation({ operationId: 'issueCard' })
  @ApiCreatedResponse({ type: IssueCardResponseBody })
  public async issueCard(
    @Req() req: AuthenticatedRequest,
    @Body() body: IssueCardRequestBody
  ): Promise<IssueCardResponseBody> {
    const requesterId = req.user.userId
    return this.commandBus.execute<IssueCardCommand, Card>(new IssueCardCommand({ ...body, requesterId }))
      .then((card) => ({
        cardId: card.cardId,
        accountId: card.accountId,
        ownerId: card.ownerId,
        brand: card.brand,
        status: card.status,
        createdAt: card.createdAt
      }))
      .catch((error) => {
        this.logger.error(error)
        throw generateErrorResponse(error.message, [
          [CardErrorMessage['The account to link could not be found.'], NotFoundException, ErrorCode.LINKED_ACCOUNT_NOT_FOUND],
          [CardErrorMessage['Only an active account can have a card issued.'], BadRequestException, ErrorCode.CARD_ISSUE_REQUIRES_ACTIVE_ACCOUNT]
        ])
      })
  }

  @Get('/cards/:cardId')
  @ApiOperation({ operationId: 'getCard' })
  @ApiOkResponse({ type: GetCardResponseBody })
  public async getCard(
    @Req() req: AuthenticatedRequest,
    @Param() param: GetCardRequestParam
  ): Promise<GetCardResponseBody> {
    const requesterId = req.user.userId
    return this.queryBus.execute<GetCardQuery, GetCardResult>(
      new GetCardQuery({ cardId: param.cardId, requesterId })
    ).catch((error) => {
      this.logger.error(error)
      throw generateErrorResponse(error.message, [
        [CardErrorMessage['Card not found.'], NotFoundException, ErrorCode.CARD_NOT_FOUND]
      ])
    })
  }
}
